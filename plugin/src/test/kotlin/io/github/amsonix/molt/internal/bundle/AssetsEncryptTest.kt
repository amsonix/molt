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
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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
        val instruction = method.implementation!!.instructions.first()
        assertTrue("must be a reference instruction", instruction is ReferenceInstruction)
        val ref = (instruction as ReferenceInstruction).reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
        assertEquals("method name must be open", "open", ref.name)
    }

    @Test
    fun remapBytes_assetsEncryptOnly_rewritesCallSites() {
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
                        listOf(call, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
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
        val instruction = method.implementation!!.instructions.first()
        val ref = (instruction as ReferenceInstruction).reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
    }

    @Test
    fun twoArgOpen_callIsRewrittenToFogAssetsTwoArgOverload() {
        val owner = "Lcom/example/app/Main;"
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
        val instruction = method.implementation!!.instructions.first() as ReferenceInstruction
        val ref = instruction.reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
        assertEquals("must be 2-arg overload", listOf("Ljava/lang/String;", "I"), ref.parameterTypes)
        val opcode = method.implementation!!.instructions.first().opcode
        assertTrue("must be invoke-static", opcode == Opcode.INVOKE_STATIC)
    }

    @Test
    fun twoArgOpen_rangeForm_isRewritten() {
        val owner = "Lcom/example/app/Main;"
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
        val instruction = method.implementation!!.instructions.first() as ReferenceInstruction
        val ref = instruction.reference as MethodReference
        assertEquals("must call FogAssets.open", "Lcom/example/app/shell/fogassets/FogAssets;", ref.definingClass)
        assertEquals("must be 2-arg overload", listOf("Ljava/lang/String;", "I"), ref.parameterTypes)
        val opcode = method.implementation!!.instructions.first().opcode
        assertTrue("must be invoke-static/range", opcode == Opcode.INVOKE_STATIC_RANGE)
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
