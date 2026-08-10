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
 */
internal data class AssetsEncryptConfig(
    val seed: Int,
    val filePatterns: List<String>,
    val fogAssetsDescriptor: String,
)

/** FogAssets 解密 + ContentProvider 初始化源码生成（与 Fog 同密钥体系）。 */
internal object FogAssetsSource {

    fun fogAssetsPackagePrefix(applicationId: String): String = "$applicationId.shell.fogassets"

    fun fogAssetsDescriptor(applicationId: String): String =
        "L${fogAssetsPackagePrefix(applicationId).replace('.', '/')}/FogAssets;"

    /** 返回 (FogAssets.java, FogAssetsInitializer.java) 两个 public 类源码。 */
    fun buildSource(applicationId: String, seed: Int): Pair<String, String> {
        val pkg = fogAssetsPackagePrefix(applicationId)
        val fogAssets = """
            package $pkg;

            public final class FogAssets {
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

            public final class FogAssetsInitializer extends android.content.ContentProvider {
                @Override
                public boolean onCreate() {
                    FogAssets.init(getContext());
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
    fun manifestSnippet(applicationId: String): String = """
        <provider xmlns:android="http://schemas.android.com/apk/res/android"
            android:name="${fogAssetsPackagePrefix(applicationId)}.FogAssetsInitializer"
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

    fun rewriteClass(classDef: ClassDef, config: AssetsEncryptConfig): ClassDef {
        // FogAssets 内部的 getAssets().open() 是解密实现，不得改写（否则自递归）。
        if (classDef.type == config.fogAssetsDescriptor) return classDef
        val direct = classDef.directMethods.map { rewriteMethod(it, config) }
        val virtual = classDef.virtualMethods.map { rewriteMethod(it, config) }
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

    private fun rewriteMethod(method: Method, config: AssetsEncryptConfig): Method {
        val implementation = method.implementation ?: return method
        val mmi = MutableMethodImplementation(implementation)
        val instructions = mmi.instructions
        var changed = false
        for (index in instructions.indices) {
            val instruction = instructions[index]
            val argCount = assetManagerOpenCall(instruction) ?: continue
            // 仅当清单非空时改写（清单为空 = 功能关闭但类仍注入）。
            if (config.filePatterns.isEmpty()) continue
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

    fun patchZipInPlace(zipFile: File, config: AssetsEncryptConfig, assetsPrefix: String) {
        if (config.filePatterns.isEmpty()) return
        val temp = File.createTempFile("molt-assets-encrypt", ".zip", zipFile.parentFile)
        var encrypted = 0
        try {
            java.util.zip.ZipFile(zipFile).use { zipIn ->
                java.util.zip.ZipOutputStream(temp.outputStream().buffered()).use { zipOut ->
                    zipIn.entries().asSequence().forEach { entry ->
                        if (entry.isDirectory) {
                            io.github.amsonix.molt.internal.bundle.ZipEntryWriter.copy(
                                zipOut, zipIn, entry, entry.name,
                            )
                            return@forEach
                        }
                        if (entry.name.startsWith(assetsPrefix) && matches(entry.name, config.filePatterns)) {
                            val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                            warnOnWebViewReferences(zipFile, entry.name, bytes)
                            // 密钥按相对 assets 的路径派生（与运行时 FogAssets.open(path) 一致）。
                            val relativePath = entry.name.removePrefix(assetsPrefix)
                            val encryptedBytes = encryptBytes(relativePath, bytes, config.seed)
                            io.github.amsonix.molt.internal.bundle.ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = encryptedBytes,
                                contentsChanged = true,
                            )
                            encrypted++
                        } else {
                            io.github.amsonix.molt.internal.bundle.ZipEntryWriter.copy(
                                zipOut, zipIn, entry, entry.name,
                            )
                        }
                    }
                }
            }
            if (encrypted > 0) {
                temp.copyTo(zipFile, overwrite = true)
            }
        } finally {
            temp.delete()
        }
        java.util.logging.Logger.getLogger(ZipAssetEncryptor::class.java.name)
            .info("molt: assets encrypted=$encrypted")
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
    private fun warnOnWebViewReferences(zipFile: File, entryName: String, bytes: ByteArray) {
        val ext = entryName.substringAfterLast('.', "")
        if (ext !in setOf("html", "htm", "js")) return
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
        if (!text.contains("file:///android_asset/")) return
        val referenced = WEBVIEW_ASSET_REFERENCE.findAll(text).map { it.value }.toList().take(5)
        java.util.logging.Logger.getLogger(ZipAssetEncryptor::class.java.name)
            .warning(
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
