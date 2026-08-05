package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Test
import java.io.File

/** 生成完整 post-R8 mapping rewrite APK 供设备验证。 */
class DexMappingRewriteApkGeneratorTest {

    @Test
    fun generateMappingRewriteApk() {
        val root = IntegrationTestAssumptions.projectRoot()
        val unsigned = IntegrationTestAssumptions.assumeIntegrationApk(root)
        val componentJson = IntegrationTestAssumptions.assumeComponentMapping(root)
        val viewJson = IntegrationTestAssumptions.assumeViewMapping(root)
        val r8Mapping = DexIntegrationFixture.r8Mapping(root)
        val componentMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(componentJson.readText()),
            r8Mapping.takeIf { it.isFile },
        )
        val viewMapping = R8MappingAliasExpander.expand(
            RenameMapping.fromJson(viewJson.readText()),
            r8Mapping.takeIf { it.isFile },
        )
        val outApk = File(
            root,
            "app/build/outputs/apk/google/release/mapping-rewrite-07300746.apk",
        )
        ZipPostR8RenameProcessor.processZip(
            unsigned,
            outApk,
            ZipPostR8RenameProcessor.Config(
                componentMapping = componentMapping,
                viewMapping = viewMapping,
            ),
        )
        println("written ${outApk.path}")
    }
}
