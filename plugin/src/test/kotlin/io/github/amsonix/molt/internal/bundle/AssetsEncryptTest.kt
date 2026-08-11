package io.github.amsonix.molt.internal.bundle

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class AssetsEncryptTest {

    private val config = AssetsEncryptConfig(
        seed = 7,
        filePatterns = listOf("*.json", "*.cfg"),
        fogAssetsDescriptor = "Lcom/example/app/shell/fogassets/FogAssets;",
    )

    @Test
    fun zipEncrypt_decrypt_roundTrip_restoresBytes() {
        val original = """{"api": "https://example.com", "key": "v1"}""".encodeToByteArray()
        val encrypted = ZipAssetEncryptor.encryptBytes("assets/config.json", original, config.seed)
        assertFalse("must be encrypted", encrypted.contentEquals(original))
        val decrypted = ZipAssetEncryptor.encryptBytes("assets/config.json", encrypted, config.seed)
        assertTrue("XOR self-inverse must restore original", decrypted.contentEquals(original))
    }

    @Test
    fun assetManagerOpen_callIsRewrittenToFogAssets() {
        val owner = "Lcom/example/app/Main;"
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("secret.cfg"),
        )
        val call = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(constString, call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val input = buildDex(clazz)
        val output = DexAssetEncryptor.rewriteClass(openDex(input).classes.first(), config)
        val rebuilt = buildDex(output)
        val parsed = openDex(rebuilt)

        val method = parsed.classes.first().directMethods.first()
        val instruction = method.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().last()
        val ref = instruction.reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
        assertEquals("method name must be open", "open", ref.name)
    }

    @Test
    fun remapBytes_assetsEncryptOnly_rewritesCallSites() {
        val owner = "Lcom/example/app/Main;"
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("secret.cfg"),
        )
        val call = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(constString, call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val input = buildDex(clazz)
        val emptyMapping = io.github.amsonix.molt.internal.rename.RenameMapping.fromForward(emptyMap())
        val output = DexInPlaceRenameEngine.remapBytes(input, emptyMapping, null, null, null, config)
        assertFalse("assets-only dex must still be rewritten", output.contentEquals(input))

        val parsed = openDex(output)
        val method = parsed.classes.first().directMethods.first()
        val instruction = method.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().last()
        val ref = instruction.reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
    }

    @Test
    fun twoArgOpen_callIsRewrittenToFogAssetsTwoArgOverload() {
        val owner = "Lcom/example/app/Main;"
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("secret.cfg"),
        )
        val call = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            3,
            0,
            1,
            2,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;", "I"),
                "Ljava/io/InputStream;",
            ),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        3,
                        listOf(constString, call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val input = buildDex(clazz)
        val output = DexAssetEncryptor.rewriteClass(openDex(input).classes.first(), config)
        val rebuilt = buildDex(output)
        val parsed = openDex(rebuilt)

        val method = parsed.classes.first().directMethods.first()
        val instruction = method.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().last()
        val ref = instruction.reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
        assertEquals("must be 2-arg overload", listOf("Ljava/lang/String;", "I"), ref.parameterTypes)
        val opcode = method.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().last().opcode
        assertTrue("must be invoke-static", opcode == Opcode.INVOKE_STATIC)
    }

    @Test
    fun twoArgOpen_rangeForm_isRewritten() {
        val owner = "Lcom/example/app/Main;"
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            21,
            ImmutableStringReference("secret.cfg"),
        )
        val call = ImmutableInstruction3rc(
            Opcode.INVOKE_VIRTUAL_RANGE,
            20,
            3,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;", "I"),
                "Ljava/io/InputStream;",
            ),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        24,
                        listOf(constString, call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val input = buildDex(clazz)
        val output = DexAssetEncryptor.rewriteClass(openDex(input).classes.first(), config)
        val rebuilt = buildDex(output)
        val parsed = openDex(rebuilt)

        val method = parsed.classes.first().directMethods.first()
        val instruction = method.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().last()
        val ref = instruction.reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
        assertEquals("must be 2-arg overload", listOf("Ljava/lang/String;", "I"), ref.parameterTypes)
        val opcode = method.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().last().opcode
        assertTrue("must be invoke-static/range", opcode == Opcode.INVOKE_STATIC_RANGE)
    }

    @Test
    fun fogAssetsClass_ownOpenCall_isNotRewritten() {
        val owner = "Lcom/example/app/shell/fogassets/FogAssets;"
        val openTarget = ImmutableMethodReference(
            "Landroid/content/res/AssetManager;",
            "open",
            listOf("Ljava/lang/String;"),
            "Ljava/io/InputStream;",
        )
        val call = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            openTarget,
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "open",
                    listOf(org.jf.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/String;", emptySet(), null)),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val input = buildDex(clazz)
        val output = DexAssetEncryptor.rewriteClass(openDex(input).classes.first(), config)
        val rebuilt = buildDex(output)
        val parsed = openDex(rebuilt)

        val method = parsed.classes.first().directMethods.first()
        val instruction = method.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>().last()
        val ref = instruction.reference as MethodReference
        assertEquals(
            "FogAssets 自身调用必须保持 AssetManager.open（否则自递归）",
            "Landroid/content/res/AssetManager;",
            ref.definingClass,
        )
    }

    @Test
    fun scanFdReferencedPaths_extractsConstStringFileNames() {
        val owner = "Lcom/example/app/Main;"
        val call = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "openFd",
                listOf("Ljava/lang/String;"),
                "Landroid/content/res/AssetFileDescriptor;",
            ),
        )
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("video/intro.mp4"),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "readFd",
                    emptyList(),
                    "Landroid/content/res/AssetFileDescriptor;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(constString, call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val paths = DexAssetEncryptor.scanFdReferencedPaths(openDex(buildDex(clazz)))
        assertEquals(setOf("video/intro.mp4"), paths)
    }

    @Test
    fun scanOpenReferencedPaths_extractsConstStringFileNames() {
        val owner = "Lcom/example/app/Main;"
        val call = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("video/intro.mp4"),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(constString, call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val paths = DexAssetEncryptor.scanOpenReferencedPaths(openDex(buildDex(clazz)), "Lcom/example/app/shell/fogassets/FogAssets;")
        assertEquals(setOf("video/intro.mp4"), paths)
    }

    @Test
    fun scanOpenReferencedPaths_ignoresFdAndDynamicCallers() {
        val owner = "Lcom/example/app/Main;"
        val openCall = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val fdCall = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "openFd",
                listOf("Ljava/lang/String;"),
                "Landroid/content/res/AssetFileDescriptor;",
            ),
        )
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("video/other.mp4"),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "mix",
                    emptyList(),
                    "Ljava/lang/Object;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        3,
                        listOf(
                            constString,
                            fdCall,
                            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val paths = DexAssetEncryptor.scanOpenReferencedPaths(openDex(buildDex(clazz)), "Lcom/example/app/shell/fogassets/FogAssets;")
        assertTrue("fd 调用不得计入（open 扫描不匹配 openFd）", paths.isEmpty())
    }

    @Test
    fun patchZip_fdReferencedFileStaysPlaintext() {
        val owner = "Lcom/example/app/Main;"
        val fdCall = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "openFd",
                listOf("Ljava/lang/String;"),
                "Landroid/content/res/AssetFileDescriptor;",
            ),
        )
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("video.mp4"),
        )
        val openCall = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val cfgConstString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("config.json"),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "readFd",
                    emptyList(),
                    "Landroid/content/res/AssetFileDescriptor;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(constString, fdCall, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(cfgConstString, openCall, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val dexBytes = buildDex(clazz)
        val zip = File.createTempFile("molt-assets-test", ".apk")
        try {
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { zout ->
                zout.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                zout.write(dexBytes)
                zout.closeEntry()
                zout.putNextEntry(java.util.zip.ZipEntry("assets/video.mp4").also {
                    it.method = java.util.zip.ZipEntry.STORED
                    val bytes = "MP4PLAINTEXT".encodeToByteArray()
                    it.size = bytes.size.toLong()
                    it.compressedSize = bytes.size.toLong()
                    it.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                })
                zout.write("MP4PLAINTEXT".encodeToByteArray())
                zout.closeEntry()
                zout.putNextEntry(java.util.zip.ZipEntry("assets/config.json"))
                zout.write("""{"api": "https://example.com"}""".encodeToByteArray())
                zout.closeEntry()
                zout.putNextEntry(java.util.zip.ZipEntry("assets/raw.bin"))
                zout.write("RAWPLAINTEXT".encodeToByteArray())
                zout.closeEntry()
            }
            val configWithVideo = config.copy(filePatterns = listOf("*.json", "*.mp4", "*.bin"))
            val result = ZipAssetEncryptor.patchZipInPlace(zip, configWithVideo, "assets/")
            assertEquals(2, result.encrypted)
            assertEquals(1, result.mediaSkipped)

            java.util.zip.ZipFile(zip).use { zf ->
                val video = zf.getInputStream(zf.getEntry("assets/video.mp4")).use { it.readBytes() }
                assertTrue(
                    "openFd 引用的文件必须保持明文",
                    String(video, Charsets.UTF_8).contains("MP4PLAINTEXT"),
                )
                val cfg = zf.getInputStream(zf.getEntry("assets/config.json")).use { it.readBytes() }
                assertFalse(
                    "常量 open() 调用点的文件必须加密",
                    String(cfg, Charsets.UTF_8).contains("https://example.com"),
                )
                assertEquals(
                    "加密条目必须强制 DEFLATED（openFd 遇压缩必抛 IOException，杜绝密文 fd）",
                    java.util.zip.ZipEntry.DEFLATED,
                    zf.getEntry("assets/config.json").method,
                )
                val raw = zf.getInputStream(zf.getEntry("assets/raw.bin")).use { it.readBytes() }
                assertFalse(
                    "无调用点文件现在也加密（运行时 FogAssets 判定解密）",
                    String(raw, Charsets.UTF_8).contains("RAWPLAINTEXT"),
                )
            }
        } finally {
            zip.delete()
        }
    }

    @Test
    fun scanOpenReferencedPaths_matchesRewrittenFogAssetsCalls() {
        val fogDescriptor = "Lcom/example/app/shell/fogassets/FogAssets;"
        val owner = "Lcom/example/app/Main;"
        val rewrittenCall = ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            1,
            1,
            0,
            0,
            0,
            0,
            ImmutableMethodReference(
                fogDescriptor,
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("secret.cfg"),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(constString, rewrittenCall, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val paths = DexAssetEncryptor.scanOpenReferencedPaths(openDex(buildDex(clazz)), fogDescriptor)
        assertEquals(
            "扫描在 dex 改写之后执行，已改写为 FogAssets.open 的调用点也必须计入",
            setOf("secret.cfg"),
            paths,
        )
    }

    @Test
    fun patchZip_mediaExtensionStaysPlaintextEvenWithOpenCall() {
        val owner = "Lcom/example/app/Main;"
        val openCall = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val constString = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            1,
            ImmutableStringReference("audio.mp3"),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    owner,
                    "read",
                    emptyList(),
                    "Ljava/io/InputStream;",
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        2,
                        listOf(constString, openCall, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
            emptyList(),
        )
        val dexBytes = buildDex(clazz)
        val zip = File.createTempFile("molt-assets-test", ".apk")
        try {
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { zout ->
                zout.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                zout.write(dexBytes)
                zout.closeEntry()
                zout.putNextEntry(java.util.zip.ZipEntry("assets/audio.mp3"))
                zout.write("AUDIOPLAINTEXT".encodeToByteArray())
                zout.closeEntry()
            }
            val result = ZipAssetEncryptor.patchZipInPlace(zip, config.copy(filePatterns = listOf("*.mp3")), "assets/")
            assertEquals(0, result.encrypted)
            assertEquals(1, result.mediaSkipped)

            java.util.zip.ZipFile(zip).use { zf ->
                val audio = zf.getInputStream(zf.getEntry("assets/audio.mp3")).use { it.readBytes() }
                assertTrue(
                    "媒体扩展名即使有 open() 调用点也必须保持明文（AAPT no-compress 意图层）",
                    String(audio, Charsets.UTF_8).contains("AUDIOPLAINTEXT"),
                )
            }
        } finally {
            zip.delete()
        }
    }

    @Test
    fun mediaExtensionSet_coversAaptFontsAndWebp() {
        val owner = "Lcom/example/app/Main;"
        val openCall = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Landroid/content/res/AssetManager;",
                "open",
                listOf("Ljava/lang/String;"),
                "Ljava/io/InputStream;",
            ),
        )
        val zip = File.createTempFile("molt-assets-test", ".apk")
        try {
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { zout ->
                val extensions = listOf("font.ttf", "font.otf", "font.ttc", "image.webp", "video.mp4", "sound.mp3")
                for ((index, name) in extensions.withIndex()) {
                    val constString = ImmutableInstruction21c(
                        Opcode.CONST_STRING,
                        1,
                        ImmutableStringReference(name),
                    )
                    val clazz = ImmutableClassDef(
                        owner,
                        AccessFlags.PUBLIC.value,
                        "Ljava/lang/Object;",
                        emptyList(),
                        null,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        listOf(
                            ImmutableMethod(
                                owner,
                                "read",
                                emptyList(),
                                "Ljava/io/InputStream;",
                                AccessFlags.PUBLIC.value,
                                emptySet(),
                                emptySet(),
                                ImmutableMethodImplementation(
                                    2,
                                    listOf(constString, openCall, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                                    emptyList(),
                                    emptyList(),
                                ),
                            ),
                        ),
                        emptyList(),
                    )
                    val dexBytes = buildDex(clazz)
                    zout.putNextEntry(java.util.zip.ZipEntry("classes${index}.dex"))
                    zout.write(dexBytes)
                    zout.closeEntry()
                    zout.putNextEntry(java.util.zip.ZipEntry("assets/$name"))
                    zout.write("PLAINTEXT".encodeToByteArray())
                    zout.closeEntry()
                }
            }
            val result = ZipAssetEncryptor.patchZipInPlace(
                zip,
                config.copy(filePatterns = listOf("*.ttf", "*.otf", "*.ttc", "*.webp", "*.mp4", "*.mp3")),
                "assets/",
            )
            assertEquals(0, result.encrypted)
            assertEquals(
                "AAPT no-compress 集合必须覆盖字体(ttf/otf/ttc)与 webp（AGP 8.0/8.13 实证新增）",
                6,
                result.mediaSkipped,
            )
        } finally {
            zip.delete()
        }
    }

    private fun buildDex(vararg classes: org.jf.dexlib2.iface.ClassDef): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    private fun openDex(bytes: ByteArray): DexBackedDexFile =
        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(bytes)) as DexBackedDexFile
}
