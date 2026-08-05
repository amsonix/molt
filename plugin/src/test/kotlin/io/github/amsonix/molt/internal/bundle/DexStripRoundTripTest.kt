package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile

/** 组件 in-place 改包后 dex 须能被 dexlib2 完整解析。 */
class DexStripRoundTripTest {

    @Test
    fun inPlaceComponentRename_parsesWithDexlib2() {
        val root = IntegrationTestAssumptions.projectRoot()
        val mapping = loadComponentMapping(root)
        assumeTrue("component mapping missing or expand failed", mapping != null)
        val preDex = loadClassesDex(root)
        val allDex = loadAllDex(root)
        assumeTrue("APK has no dex entries", allDex.isNotEmpty())
        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, mapping!!)
        val outDex = DexInPlaceRenameEngine.remapBytes(preDex, mapping, rewritePlan)
        val merged = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(outDex),
        )
        var classCount = 0
        for (ignored in merged.classes) {
            classCount++
        }
        assertTrue(classCount > 0)
        assertTrue(classCount >= countClasses(preDex))
    }

    private fun countClasses(dexBytes: ByteArray): Int {
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(dexBytes),
        )
        var count = 0
        for (ignored in dexFile.classes) {
            count++
        }
        return count
    }

    private fun loadComponentMapping(root: java.io.File): RenameMapping? {
        val component = IntegrationTestAssumptions.assumeComponentMapping(root)
        val r8Mapping = DexIntegrationFixture.r8Mapping(root)
        return R8MappingAliasExpander.expand(
            RenameMapping.fromJson(component.readText()),
            r8Mapping.takeIf { it.isFile },
        )
    }

    private fun loadClassesDex(root: java.io.File): ByteArray {
        val apk = IntegrationTestAssumptions.assumeIntegrationApk(root)
        ZipFile(apk).use { zip ->
            return zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
        }
    }

    private fun loadAllDex(root: java.io.File): List<ByteArray> {
        val apk = IntegrationTestAssumptions.assumeIntegrationApk(root)
        ZipFile(apk).use { zip ->
            return zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
                .map { zip.getInputStream(it).readBytes() }
                .toList()
        }
    }
}
