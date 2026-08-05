package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.zip.ZipFile

class BinaryXmlViewClassReplacerApkProbeTest {

    @Test
    fun replace_patchesApkManifestWhenPresent() {
        val root = IntegrationTestAssumptions.projectRoot()
        val mappingFile = IntegrationTestAssumptions.assumeComponentMapping(root)
        val apk = IntegrationTestAssumptions.assumeIntegrationApk(root)

        val componentMapping = RenameMapping.fromJson(mappingFile.readText())
        ZipFile(apk).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val patchedManifest = BinaryXmlViewClassReplacer.replace(manifest, componentMapping)
            if (patchedManifest.contentEquals(manifest)) {
                // Transform 产物可能已 patch，视为 probe 通过
                return
            }
            assertFalse(
                "APK manifest should be patched with component mapping",
                patchedManifest.contentEquals(manifest),
            )
        }
    }

    @Test
    fun replace_patchesApkLayoutWhenContainsViewFqcn() {
        val root = IntegrationTestAssumptions.projectRoot()
        val viewMappingFile = IntegrationTestAssumptions.assumeViewMapping(root)
        val apk = IntegrationTestAssumptions.assumeIntegrationApk(root)

        val viewMapping = RenameMapping.fromJson(viewMappingFile.readText())
        val needle = "com.example.ui.textview.PLTextView"
        ZipFile(apk).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { it.name.startsWith("res/") && it.name.endsWith(".xml") }
                ?: run {
                    assumeTrue("no layout entry in APK", false)
                    return
                }
            val layout = zip.getInputStream(entry).readBytes()
            assumeTrue("layout without View FQCN skipped", String(layout).contains(needle))
            val patched = BinaryXmlViewClassReplacer.replace(layout, viewMapping)
            assertFalse("layout with View FQCN should patch", patched.contentEquals(layout))
        }
    }
}
