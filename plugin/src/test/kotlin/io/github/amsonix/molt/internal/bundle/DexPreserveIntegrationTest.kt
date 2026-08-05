package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** 组件 + View 共用 in-place DEX 引擎，Zip 处理器一致性。 */
class DexPreserveIntegrationTest {

    @Test
    fun zipProcessor_classesDex_matchesInPlaceEngine() {
        val root = IntegrationTestAssumptions.projectRoot()
        val unsigned = IntegrationTestAssumptions.assumeIntegrationApk(root)
        val component = IntegrationTestAssumptions.assumeComponentMapping(root)
        val view = IntegrationTestAssumptions.assumeViewMapping(root)
        val r8Mapping = DexIntegrationFixture.r8Mapping(root)
        val componentMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(component.readText()),
            r8Mapping.takeIf { it.isFile },
        )
        val viewMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(view.readText()),
            r8Mapping.takeIf { it.isFile },
        )
        assumeTrue("component mapping expand failed", componentMapping != null)
        assumeTrue("view mapping expand failed", viewMapping != null)
        val dexMapping = componentMapping!!.mergedWith(viewMapping!!)
        val preDex = ZipFile(unsigned).use { it.getInputStream(it.getEntry("classes.dex")).readBytes() }
        val allDex = loadAllDexFromApk(unsigned)
        assumeTrue("APK has no dex entries", allDex.isNotEmpty())
        val rewritePlan = DexInPlaceRenameEngine.buildRewritePlan(allDex, dexMapping)
        val engineDex = DexInPlaceRenameEngine.remapBytes(preDex, dexMapping, rewritePlan)
        val outApk = File.createTempFile("zip-probe-unified", ".apk")
        ZipPostR8RenameProcessor.processZip(
            unsigned,
            outApk,
            ZipPostR8RenameProcessor.Config(
                componentMapping = componentMapping,
                viewMapping = viewMapping,
            ),
        )
        val zipDex = ZipFile(outApk).use { it.getInputStream(it.getEntry("classes.dex")).readBytes() }
        org.junit.Assert.assertArrayEquals(engineDex, zipDex)
        val dexEntryCount = ZipFile(outApk).use { zip ->
            zip.entries().asSequence().count { it.name.matches(Regex("classes\\d*\\.dex")) }
        }
        val preEntryCount = ZipFile(unsigned).use { zip ->
            zip.entries().asSequence().count { it.name.matches(Regex("classes\\d*\\.dex")) }
        }
        org.junit.Assert.assertEquals(preEntryCount, dexEntryCount)
    }

    private fun loadAllDexFromApk(apk: File): List<ByteArray> {
        ZipFile(apk).use { zip ->
            return zip.entries().asSequence()
                .filter { it.name.matches(Regex("(^|.*/)classes\\d*\\.dex$")) }
                .map { zip.getInputStream(it).readBytes() }
                .toList()
        }
    }
}
