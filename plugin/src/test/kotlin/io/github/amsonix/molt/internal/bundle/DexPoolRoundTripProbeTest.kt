package io.github.amsonix.molt.internal.bundle

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.dexbacked.instruction.DexBackedInstruction
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile

/** 探测 DexPool 重编码是否本身就会破坏 Compose DrawScope.default 方法。 */
class DexPoolRoundTripProbeTest {

    @Test
    fun identityDexPoolRoundTrip_preservesDrawScopeDefaultInsns() {
        val dexBytes = loadPreDex()
        val before = extractMethodInsns(
            dexBytes,
            "Landroidx/compose/ui/graphics/drawscope/DrawScope;",
            "drawRect-n-J9OG0\$default",
        )
        assumeTrue("DrawScope default method not found in pre-dex", before != null)
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(dexBytes),
        )
        val pool = DexPool(dexFile.opcodes)
        for (classDef in dexFile.classes) {
            pool.internClass(classDef)
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        val after = extractMethodInsns(
            store.data,
            "Landroidx/compose/ui/graphics/drawscope/DrawScope;",
            "drawRect-n-J9OG0\$default",
        )
        assertArrayEquals(before, after)
    }

    @Test
    fun immutableClassDefOfRoundTrip_preservesDrawScopeDefaultInsns() {
        val dexBytes = loadPreDex()
        val before = extractMethodInsns(
            dexBytes,
            "Landroidx/compose/ui/graphics/drawscope/DrawScope;",
            "drawRect-n-J9OG0\$default",
        )
        assumeTrue("DrawScope default method not found in pre-dex", before != null)
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(dexBytes),
        )
        val pool = DexPool(dexFile.opcodes)
        for (classDef in dexFile.classes) {
            pool.internClass(ImmutableClassDef.of(classDef))
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        val after = extractMethodInsns(
            store.data,
            "Landroidx/compose/ui/graphics/drawscope/DrawScope;",
            "drawRect-n-J9OG0\$default",
        )
        assertArrayEquals(before, after)
    }

    private fun loadPreDex(): ByteArray {
        val root = IntegrationTestAssumptions.projectRoot()
        val apk = IntegrationTestAssumptions.assumeIntegrationApk(root)
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("classes.dex")
            assumeTrue("classes.dex missing in integration APK", entry != null)
            return zip.getInputStream(entry!!).readBytes()
        }
    }

    private fun extractMethodInsns(
        dexBytes: ByteArray,
        typeDescriptor: String,
        methodName: String,
    ): ByteArray? {
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(dexBytes),
        ) as DexBackedDexFile
        val classDef = dexFile.classes.firstOrNull { it.type == typeDescriptor } ?: return null
        val method = classDef.methods.firstOrNull { it.name == methodName } ?: return null
        val impl = method.implementation ?: return null
        val buffer = dexFile.buffer
        val out = java.io.ByteArrayOutputStream()
        for (instruction in impl.instructions) {
            val backed = instruction as DexBackedInstruction
            val start = backed.instructionStart
            val length = backed.codeUnits * 2
            out.write(buffer.readByteRange(start, length))
        }
        return out.toByteArray()
    }
}
