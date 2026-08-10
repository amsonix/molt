package io.github.amsonix.molt.internal.bundle

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DexPerturberTest {

    private fun buildClass(
        descriptor: String,
        methodCount: Int = 2,
    ): ImmutableClassDef = ImmutableClassDef(
        "L${descriptor.replace('.', '/')};",
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        emptyList(),
        null,
        emptyList(),
        emptyList(),
        emptyList(),
        (0 until methodCount).map { index ->
            ImmutableMethod(
                "L${descriptor.replace('.', '/')};",
                "m$index",
                emptyList(),
                "V",
                AccessFlags.PUBLIC.value,
                emptySet(),
                emptySet(),
                ImmutableMethodImplementation(
                    1,
                    listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    emptyList(),
                    emptyList(),
                ),
            )
        },
        emptyList(),
    )

    private fun buildDex(vararg classes: org.jf.dexlib2.iface.ClassDef): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    private fun openDex(bytes: ByteArray): DexBackedDexFile =
        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(bytes)) as DexBackedDexFile

    private fun nopCount(dexFile: DexBackedDexFile): Int =
        dexFile.classes.flatMap { it.directMethods + it.virtualMethods }
            .mapNotNull { it.implementation }
            .flatMap { it.instructions }
            .count { it.opcode == Opcode.NOP }

    @Test
    fun perturb_injectsNopsWithinRange_andKeepsDexValid() {
        val input = buildDex(buildClass("com.example.app.Main"), buildClass("com.example.other.Lib"))
        val config = DexPerturbationConfig(seed = 7, intensity = "light")

        val output = DexPerturber.rewriteClass(openDex(input).classes.first(), config)

        // dex 有效（可重新打开）
        val rebuilt = buildDex(output)
        val parsed = openDex(rebuilt)
        assertEquals("method count must be preserved", 2, parsed.classes.first().directMethods.toList().size)
        val nops = parsed.classes.first().directMethods
            .mapNotNull { it.implementation }
            .flatMap { it.instructions }
            .count { it.opcode == Opcode.NOP }
        assertTrue("nops must be in light range (1-3 per method, 2 methods): $nops", nops in 2..6)
    }

    @Test
    fun perturb_isDeterministicForSameSeed() {
        val input = buildDex(buildClass("com.example.app.Main", methodCount = 3))
        val a = buildDex(DexPerturber.rewriteClass(openDex(input).classes.first(), DexPerturbationConfig(seed = 42)))
        val b = buildDex(DexPerturber.rewriteClass(openDex(input).classes.first(), DexPerturbationConfig(seed = 42)))
        assertEquals("same seed must reproduce identical output", a.contentEquals(b), true)

        val c = buildDex(DexPerturber.rewriteClass(openDex(input).classes.first(), DexPerturbationConfig(seed = 43)))
        assertTrue("different seed must differ", !a.contentEquals(c))
    }

    @Test
    fun remapBytes_perturbOnly_withoutStringEncrypt_doesNotNpeAndPerturbs() {
        val input = buildDex(buildClass("com.example.app.Main", methodCount = 2))
        val emptyMapping = io.github.amsonix.molt.internal.rename.RenameMapping.fromForward(emptyMap())
        val config = DexPerturbationConfig(seed = 7, intensity = "light")

        val output = DexInPlaceRenameEngine.remapBytes(input, emptyMapping, null, null, config, null)

        assertFalse("perturb-only dex must be rewritten (regression: stringConfig==null NPE)", output.contentEquals(input))
        val parsed = openDex(output)
        val nops = parsed.classes.first().directMethods
            .mapNotNull { it.implementation }
            .flatMap { it.instructions }
            .count { it.opcode == Opcode.NOP }
        assertTrue("nops must be present: $nops", nops > 0)
    }
}
