package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.util.SeedRandom
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File

/**
 * assets 文本加密配置。
 *
 * [filePatterns]：声明清单（文件名 glob）；命中的 assets 文件在 transform 阶段加密，
 * DEX 中 `AssetManager.open(String)` 调用点改写为 `FogAssets.open(String)`（运行时解密）。
 * WebView / SDK 自读文件、openFd 调用不适用——不要加入清单。
 *
 * STORED 条目（AAPT no-compress 媒体）天然不加密——openFd 依赖 STORED 才能返回 fd，
 * 加密会破坏 fd 场景；DEFLATED 条目经常量 `open()` 调用点改写后解密。
 */
internal data class AssetsEncryptConfig(
    val seed: Int,
    val filePatterns: List<String>,
    val fogAssetsDescriptor: String,
)

/** FogAssets 解密 + ContentProvider 初始化源码生成（与 Fog 同密钥体系）。 */
internal object FogAssetsSource {

    fun fogAssetsPackagePrefix(applicationId: String): String = "$applicationId.shell.fogassets"

    /** 解密类名由 seed 派生（每次构建不同）——防"grep 固定类名"的自动化提取。 */
    fun fogAssetsClassName(seed: Int): String {
        val random = SeedRandom.create(seed, "fogassets-class")
        val letters = "abcdefghijklmnopqrstuvwxyz"
        return buildString {
            append('F')
            repeat(3 + random.nextInt(3)) { append(letters[random.nextInt(letters.length)]) }
            append(random.nextInt(10))
        }
    }

    fun fogAssetsInitializerClassName(seed: Int): String {
        val random = SeedRandom.create(seed, "fogassets-init")
        val letters = "abcdefghijklmnopqrstuvwxyz"
        return buildString {
            append('I')
            repeat(3 + random.nextInt(3)) { append(letters[random.nextInt(letters.length)]) }
            append(random.nextInt(10))
        }
    }

    fun fogAssetsDescriptor(applicationId: String, seed: Int): String =
        "L${fogAssetsPackagePrefix(applicationId).replace('.', '/')}/${fogAssetsClassName(seed)};"

    /** 返回 (FogAssets.java, FogAssetsInitializer.java) 两个 public 类源码。 */
    fun buildSource(applicationId: String, seed: Int): Pair<String, String> {
        val pkg = fogAssetsPackagePrefix(applicationId)
        val className = fogAssetsClassName(seed)
        val initializerName = fogAssetsInitializerClassName(seed)
        val fogAssets = """
            package $pkg;

            public final class $className {
                private static final int SEED = $seed;
                private static android.content.Context context;

                public static void init(android.content.Context c) {
                    context = c.getApplicationContext();
                }

                public static java.io.InputStream open(String path) throws java.io.IOException {
                    if (context == null) {
                        throw new IllegalStateException("FogAssets not initialized");
                    }
                    java.io.InputStream raw = context.getAssets().open(path);
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    byte[] tmp = new byte[4096];
                    int n;
                    while ((n = raw.read(tmp)) > 0) {
                        buffer.write(tmp, 0, n);
                    }
                    raw.close();
                    byte[] data = buffer.toByteArray();
                    int key = SEED ^ path.hashCode();
                    for (int i = 0; i < data.length; i++) {
                        data[i] ^= (byte) (key ^ (i & 0xFF));
                    }
                    return new java.io.ByteArrayInputStream(data);
                }

                public static java.io.InputStream open(String path, int mode) throws java.io.IOException {
                    return open(path);
                }
            }
        """.trimIndent()
        val initializer = """
            package $pkg;

            public final class $initializerName extends android.content.ContentProvider {
                @Override
                public boolean onCreate() {
                    $className.init(getContext());
                    return true;
                }

                @Override
                public android.database.Cursor query(android.net.Uri uri, String[] projection,
                        String selection, String[] selectionArgs, String sortOrder) {
                    return null;
                }

                @Override
                public String getType(android.net.Uri uri) {
                    return null;
                }

                @Override
                public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {
                    return null;
                }

                @Override
                public int delete(android.net.Uri uri, String selection, String[] selectionArgs) {
                    return 0;
                }

                @Override
                public int update(android.net.Uri uri, android.content.ContentValues values,
                        String selection, String[] selectionArgs) {
                    return 0;
                }
            }
        """.trimIndent()
        return fogAssets to initializer
    }

    /** ContentProvider manifest 声明 snippet（authority 需全局唯一）。 */
    fun manifestSnippet(applicationId: String, seed: Int): String = """
        <provider xmlns:android="http://schemas.android.com/apk/res/android"
            android:name="${fogAssetsPackagePrefix(applicationId)}.${fogAssetsInitializerClassName(seed)}"
            android:authorities="$applicationId.fogassets"
            android:exported="false"
            android:multiprocess="false" />
    """.trimIndent()
}

/**
 * post-R8 DEX：把 `AssetManager.open(String)` 调用点改写为 `FogAssets.open(String)`。
 * 目标寄存器（AssetManager 实例）被丢弃，参数从 2 变 1——无需寄存器分配。
 */
internal object DexAssetEncryptor {

    private val ASSET_MANAGER = "Landroid/content/res/AssetManager;"
    private val OPEN_1ARG_SIGNATURE = listOf("Ljava/lang/String;")
    private val OPEN_2ARG_SIGNATURE = listOf("Ljava/lang/String;", "I")
    private val RETURN_TYPE = "Ljava/io/InputStream;"
    private val FD_RETURN_TYPE = "Landroid/content/res/AssetFileDescriptor;"
    private val FD_METHODS = setOf("openFd", "openNonAssetFd")

    /** 扫描 DEX 中 `openFd(String)`/`openNonAssetFd(String)` 的常量文件名参数。 */
    fun scanFdReferencedPaths(dexFile: DexBackedDexFile): Set<String> =
        scanReferencedPaths(dexFile) { reference ->
            reference.definingClass == ASSET_MANAGER &&
                reference.name in FD_METHODS &&
                reference.parameterTypes == OPEN_1ARG_SIGNATURE &&
                reference.returnType == FD_RETURN_TYPE
        }

    /**
     * 扫描 DEX 中 `open(String)`/`open(String, int)` 或已改写的 `FogAssets.open(...)`
     * 的常量文件名参数。扫描发生在 dex 改写之后——调用点已是 FogAssets.open。
     */
    fun scanOpenReferencedPaths(dexFile: DexBackedDexFile, fogAssetsDescriptor: String): Set<String> =
        scanReferencedPaths(dexFile) { reference ->
            val paramsMatch = reference.parameterTypes == OPEN_1ARG_SIGNATURE ||
                reference.parameterTypes == OPEN_2ARG_SIGNATURE
            if (reference.name != "open" || reference.returnType != RETURN_TYPE || !paramsMatch) return@scanReferencedPaths false
            reference.definingClass == ASSET_MANAGER || reference.definingClass == fogAssetsDescriptor
        }

    private fun scanReferencedPaths(
        dexFile: DexBackedDexFile,
        isTarget: (MethodReference) -> Boolean,
    ): Set<String> {
        val paths = mutableSetOf<String>()
        for (classDef in dexFile.classes) {
            for (method in classDef.directMethods + classDef.virtualMethods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                for (index in instructions.indices) {
                    val instruction = instructions[index]
                    val pathRegister = callPathRegister(instruction, isTarget) ?: continue
                    val previous = if (index > 0) instructions[index - 1] else null
                    if (previous !is org.jf.dexlib2.iface.instruction.OneRegisterInstruction) continue
                    if (previous !is org.jf.dexlib2.iface.instruction.ReferenceInstruction) continue
                    val stringRef = previous.reference as? org.jf.dexlib2.iface.reference.StringReference ?: continue
                    if (previous.registerA != pathRegister) continue
                    paths.add(stringRef.string)
                }
            }
        }
        return paths
    }

    private fun callPathRegister(
        instruction: Instruction,
        isTarget: (MethodReference) -> Boolean,
    ): Int? {
        if (instruction !is ReferenceInstruction) return null
        val reference = instruction.reference as? MethodReference ?: return null
        if (!isTarget(reference)) return null
        // invoke-static 首个参数在 registerC（无 receiver）；invoke-virtual 的 path 是第二个参数（registerD）。
        val isStatic = instruction.opcode == Opcode.INVOKE_STATIC ||
            instruction.opcode == Opcode.INVOKE_STATIC_RANGE
        return when (instruction) {
            is org.jf.dexlib2.iface.instruction.FiveRegisterInstruction ->
                if (isStatic) instruction.registerC else instruction.registerD
            is org.jf.dexlib2.iface.instruction.RegisterRangeInstruction ->
                if (isStatic) instruction.startRegister else instruction.startRegister + 1
            else -> null
        }
    }

    fun containsAssetsEncryptableClass(dexFile: DexBackedDexFile, config: AssetsEncryptConfig): Boolean {
        for (classDef in dexFile.classes) {
            if (classDef.type == config.fogAssetsDescriptor) continue
            for (method in classDef.directMethods + classDef.virtualMethods) {
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    if (assetManagerOpenCall(instruction) != null) return true
                }
            }
        }
        return false
    }

    fun rewriteClass(classDef: ClassDef, config: AssetsEncryptConfig, encryptedPaths: Set<String>): ClassDef {
        // FogAssets 内部的 getAssets().open() 是解密实现，不得改写（否则自递归）。
        if (classDef.type == config.fogAssetsDescriptor) return classDef
        val direct = classDef.directMethods.map { rewriteMethod(it, config, encryptedPaths) }
        val virtual = classDef.virtualMethods.map { rewriteMethod(it, config, encryptedPaths) }
        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            direct,
            virtual,
        )
    }

    private fun rewriteMethod(method: Method, config: AssetsEncryptConfig, encryptedPaths: Set<String>): Method {
        val implementation = method.implementation ?: return method
        val mmi = MutableMethodImplementation(implementation)
        val instructions = mmi.instructions
        var changed = false
        for (index in instructions.indices) {
            val instruction = instructions[index]
            val argCount = assetManagerOpenCall(instruction) ?: continue
            // 仅当清单非空时改写（清单为空 = 功能关闭但类仍注入）。
            if (config.filePatterns.isEmpty()) continue
            // 只改写"实际被加密"的文件调用点：清单外文件（如 .svga）保持 AssetManager.open
            // 原样，否则 FogAssets 会对明文文件执行 XOR 输出乱码（playlet SVGA 不显示崩溃链）。
            val pathRegister = when (instruction) {
                is org.jf.dexlib2.iface.instruction.FiveRegisterInstruction -> instruction.registerD
                is org.jf.dexlib2.iface.instruction.RegisterRangeInstruction -> instruction.startRegister + 1
                else -> null
            }
            if (pathRegister == null) continue
            val previous = if (index > 0) instructions[index - 1] else null
            if (previous !is org.jf.dexlib2.iface.instruction.OneRegisterInstruction) continue
            if (previous !is org.jf.dexlib2.iface.instruction.ReferenceInstruction) continue
            val stringRef = previous.reference as? org.jf.dexlib2.iface.reference.StringReference ?: continue
            if (previous.registerA != pathRegister) continue
            if (stringRef.string !in encryptedPaths) continue
            // 参数寄存器：invoke-virtual {AssetManager, p0, p1...}——p0 是第二个寄存器。
            val firstArgRegister = when (instruction) {
                is org.jf.dexlib2.iface.instruction.FiveRegisterInstruction -> instruction.registerD
                is org.jf.dexlib2.iface.instruction.RegisterRangeInstruction -> instruction.startRegister + 1
                else -> continue
            }
            val target = fogAssetsOpenMethod(config, argCount)
            val rewritten = when {
                firstArgRegister < 16 && argCount == 1 ->
                    BuilderInstruction35c(
                        Opcode.INVOKE_STATIC,
                        1,
                        firstArgRegister,
                        0,
                        0,
                        0,
                        0,
                        target,
                    )
                firstArgRegister < 16 ->
                    // 35c 保证所有寄存器 < 16：mode 寄存器 = registerE。
                    BuilderInstruction35c(
                        Opcode.INVOKE_STATIC,
                        2,
                        firstArgRegister,
                        (instruction as org.jf.dexlib2.iface.instruction.FiveRegisterInstruction).registerE,
                        0,
                        0,
                        0,
                        target,
                    )
                else ->
                    BuilderInstruction3rc(
                        Opcode.INVOKE_STATIC_RANGE,
                        firstArgRegister,
                        argCount,
                        target,
                    )
            }
            mmi.replaceInstruction(index, rewritten)
            changed = true
        }
        return if (changed) {
            ImmutableMethod(
                method.definingClass,
                method.name,
                method.parameters,
                method.returnType,
                method.accessFlags,
                method.annotations,
                method.hiddenApiRestrictions,
                mmi,
            )
        } else {
            method
        }
    }

    /** 命中 AssetManager.open 调用点，返回参数个数（1 或 2）。 */
    private fun assetManagerOpenCall(instruction: Instruction): Int? {
        if (instruction !is ReferenceInstruction) return null
        val reference = instruction.reference as? MethodReference ?: return null
        if (reference.definingClass != ASSET_MANAGER ||
            reference.name != "open" ||
            reference.returnType != RETURN_TYPE
        ) {
            return null
        }
        return when (reference.parameterTypes) {
            OPEN_1ARG_SIGNATURE -> 1
            OPEN_2ARG_SIGNATURE -> 2
            else -> null
        }
    }

    private fun fogAssetsOpenMethod(config: AssetsEncryptConfig, argCount: Int): MethodReference =
        ImmutableMethodReference(
            config.fogAssetsDescriptor,
            "open",
            if (argCount == 2) OPEN_2ARG_SIGNATURE else OPEN_1ARG_SIGNATURE,
            RETURN_TYPE,
        )
}

/** 构建期对声明清单 assets 文件加密（与 FogAssets 解密同算法）。 */
internal object ZipAssetEncryptor {

    private val WEBVIEW_ASSET_REFERENCE = Regex("""file:///android_asset/[^"'\s)]+""")

    /**
     * AAPT2 默认 no-compress 扩展名。媒体不压缩 = openFd/mmap 友好——加密它们必然破坏播放/映射场景，
     * 作为"意图层"硬排除：清单命中的媒体文件不加密，即使存在 open() 调用点。
     *
     * 基线 = AAPT2 cmd/Link.cpp 硬编码列表（android-9.0.0_r1 起）。
     * 实证（本地 aapt2 二进制 strings 提取）：AGP 8.0 起含 .webp；
     * AGP 8.13（aapt2 2.20）起新增 .ttf/.otf/.ttc（字体 no-compress，openFd/mmap 加载字体的常用路径）。
     */
    private val AAPT2_NO_COMPRESS_EXTENSIONS = setOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".wav", ".mp2", ".mp3", ".ogg",
        ".aac", ".mpg", ".mpeg", ".mid", ".midi", ".smf", ".jet", ".rtttl",
        ".imy", ".xmf", ".mp4", ".m4a", ".m4v", ".3gp", ".3gpp", ".3g2",
        ".3gpp2", ".amr", ".awb", ".wma", ".wmv", ".webm", ".mkv",
        ".ttf", ".otf", ".ttc",
    )

    private fun isAaptNoCompressAsset(path: String): Boolean =
        AAPT2_NO_COMPRESS_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }

    /**
     * 预计算"实际会被加密"的文件路径集合（相对 assets）。
     * 与 [patchZipInPlace] 的加密决策完全一致（媒体意图层 > openFd 排除 > open 常量调用点）——
     * dex 调用点改写按此集合过滤，保证"改写"与"加密"覆盖一致：
     * 清单外文件（如 .svga）调用点保持 AssetManager.open 原样，FogAssets 不会对其 XOR。
     */
    fun computeEncryptedPaths(
        zipIn: java.util.zip.ZipFile,
        config: AssetsEncryptConfig,
        assetsPrefix: String,
    ): Set<String> {
        if (config.filePatterns.isEmpty()) return emptySet()
        val openReferenced = scanReferencedAssets(zipIn) {
            DexAssetEncryptor.scanOpenReferencedPaths(it, config.fogAssetsDescriptor)
        }
        val fdReferenced = scanReferencedAssets(zipIn) { DexAssetEncryptor.scanFdReferencedPaths(it) }
        val encrypted = mutableSetOf<String>()
        zipIn.entries().asSequence().forEach { entry ->
            if (entry.isDirectory || !entry.name.startsWith(assetsPrefix)) return@forEach
            val relative = entry.name.removePrefix(assetsPrefix)
            if (!matches(entry.name, config.filePatterns)) return@forEach
            if (isAaptNoCompressAsset(relative)) return@forEach
            if (relative in fdReferenced) return@forEach
            if (relative in openReferenced) encrypted.add(relative)
        }
        return encrypted
    }


    /** patch 结果统计：加密数、openFd 引用排除数、媒体扩展名排除数、清单内无常量 open 调用点跳过数。 */
    data class Result(
        val encrypted: Int,
        val fdExcluded: Int,
        val mediaSkipped: Int,
        val noCallSite: Int,
        private val extraWarnings: List<String> = emptyList(),
    ) {
        /** 构建期告警（调用方经 Gradle logger 输出——java.util.logging 在 Gradle 不可见）。 */
        val warnings: List<String> = buildList {
            if (mediaSkipped > 0) {
                add(
                    "molt: ${mediaSkipped} manifest asset(s) are AAPT no-compress media extensions " +
                        "(jpg/png/mp4/mp3/ttf/...) and were kept plaintext — media must stay decodable",
                )
            }
            if (fdExcluded > 0) {
                add(
                    "molt: ${fdExcluded} asset(s) referenced by AssetManager.openFd/openNonAssetFd " +
                        "were kept plaintext (fd reads bypass the FogAssets rewrite)",
                )
            }
            if (noCallSite > 0) {
                add(
                    "molt: ${noCallSite} manifest asset(s) have no constant AssetManager.open() call site; " +
                        "kept plaintext (dynamic-path / reflection reads cannot be decrypted)",
                )
            }
            addAll(extraWarnings)
        }
    }

    fun patchZipInPlace(zipFile: File, config: AssetsEncryptConfig, assetsPrefix: String): Result {
        if (config.filePatterns.isEmpty()) return Result(0, 0, 0, 0)
        val temp = File.createTempFile("molt-assets-encrypt", ".zip", zipFile.parentFile)
        var encrypted = 0
        var fdExcluded = 0
        var mediaSkipped = 0
        var noCallSite = 0
        val webViewWarnings = mutableListOf<String>()
        try {
            java.util.zip.ZipFile(zipFile).use { zipIn ->
                // 加密决策由读取机制决定（不依赖 zip method——AAPT2 对极小文件也 STORED，不可靠）：
                // 1) 常量 open() 调用点 = 流式读取、可改写 → 加密；
                // 2) 常量 openFd() 调用点 = native 直读、无改写点 → 保持明文（功能正常优先）；
                // 3) 无常量调用点 = 动态拼接/反射读取 → 保持明文 + 告警（避免"加密了无人能解"）。
                // 加密条目强制 DEFLATED：native openFileDescriptor 检查 zip method 字段，
                // DEFLATED 一律抛 IOException——加密文件绝无"密文 fd"状态（免疫兜底）。
                val openReferenced = scanReferencedAssets(zipIn) {
                    DexAssetEncryptor.scanOpenReferencedPaths(it, config.fogAssetsDescriptor)
                }
                val fdReferenced = scanReferencedAssets(zipIn) { DexAssetEncryptor.scanFdReferencedPaths(it) }
                java.util.zip.ZipOutputStream(temp.outputStream().buffered()).use { zipOut ->
                    zipIn.entries().asSequence().forEach { entry ->
                        if (entry.isDirectory) {
                            io.github.amsonix.molt.internal.bundle.ZipEntryWriter.copy(
                                zipOut, zipIn, entry, entry.name,
                            )
                            return@forEach
                        }
                        val inAssets = entry.name.startsWith(assetsPrefix)
                        val relativePath = if (inAssets) entry.name.removePrefix(assetsPrefix) else entry.name
                        when {
                            inAssets && matches(entry.name, config.filePatterns) && isAaptNoCompressAsset(relativePath) -> {
                                // 意图层：AAPT 媒体扩展名 = openFd/mmap 友好文件，加密必破坏场景。
                                mediaSkipped++
                                io.github.amsonix.molt.internal.bundle.ZipEntryWriter.copy(
                                    zipOut, zipIn, entry, entry.name,
                                )
                            }
                            inAssets && relativePath in fdReferenced && matches(entry.name, config.filePatterns) -> {
                                fdExcluded++
                                io.github.amsonix.molt.internal.bundle.ZipEntryWriter.copy(
                                    zipOut, zipIn, entry, entry.name,
                                )
                            }
                            inAssets && relativePath in openReferenced && matches(entry.name, config.filePatterns) -> {
                                val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                                warnOnWebViewReferences(entry.name, bytes, webViewWarnings)
                                // 密钥按相对 assets 的路径派生（与运行时 FogAssets.open(path) 一致）。
                                val encryptedBytes = encryptBytes(relativePath, bytes, config.seed)
                                io.github.amsonix.molt.internal.bundle.ZipEntryWriter.writeBytes(
                                    zipOut = zipOut,
                                    source = entry,
                                    outputName = entry.name,
                                    bytes = encryptedBytes,
                                    contentsChanged = true,
                                    forceDeflate = true,
                                )
                                encrypted++
                            }
                            inAssets && matches(entry.name, config.filePatterns) -> {
                                noCallSite++
                                io.github.amsonix.molt.internal.bundle.ZipEntryWriter.copy(
                                    zipOut, zipIn, entry, entry.name,
                                )
                            }
                            else -> {
                                io.github.amsonix.molt.internal.bundle.ZipEntryWriter.copy(
                                    zipOut, zipIn, entry, entry.name,
                                )
                            }
                        }
                    }
                }
            }
            if (encrypted > 0 || fdExcluded > 0 || mediaSkipped > 0) {
                temp.copyTo(zipFile, overwrite = true)
            }
        } finally {
            temp.delete()
        }
        return Result(encrypted, fdExcluded, mediaSkipped, noCallSite, webViewWarnings)
    }

    private fun scanReferencedAssets(
        zipIn: java.util.zip.ZipFile,
        scanner: (org.jf.dexlib2.dexbacked.DexBackedDexFile) -> Set<String>,
    ): Set<String> {
        val referenced = mutableSetOf<String>()
        zipIn.entries().asSequence()
            .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
            .forEach { entry ->
                val dexFile = org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                    null,
                    java.io.BufferedInputStream(zipIn.getInputStream(entry)),
                )
                referenced.addAll(scanner(dexFile))
            }
        return referenced
    }

    fun encryptBytes(path: String, bytes: ByteArray, seed: Int): ByteArray {
        val key = seed xor path.hashCode()
        val out = bytes.copyOf()
        for (i in out.indices) {
            out[i] = (out[i].toInt() xor (key xor (i and 0xFF))).toByte()
        }
        return out
    }

    /**
     * WebView 盲区告警：加密清单中的 html/js 若引用 `file:///android_asset/`，
     * WebView 内部读取不走 FogAssets（DEX 无调用点），加密后必然打不开——构建期直接告警。
     */
    private fun warnOnWebViewReferences(entryName: String, bytes: ByteArray, warnings: MutableList<String>) {
        val ext = entryName.substringAfterLast('.', "")
        if (ext !in setOf("html", "htm", "js")) return
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
        if (!text.contains("file:///android_asset/")) return
        val referenced = WEBVIEW_ASSET_REFERENCE.findAll(text).map { it.value }.toList().take(5)
        warnings.add(
            "molt: $entryName references android_asset via WebView ($referenced); " +
                "these files are NOT decryptable by FogAssets — exclude them from " +
                "assetsEncrypt.filePatterns or switch to getAssets().open()",
        )
    }

    fun matches(path: String, patterns: List<String>): Boolean {
        val fileName = path.substringAfterLast('/')
        return patterns.any { pattern ->
            val target = if (pattern.contains('/')) path else fileName
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            Regex(regex).matches(target)
        }
    }

}
