package io.github.amsonix.molt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkTransformOutputSidecarTest {

    @Test
    fun publishTransformedApks_copiesApkAndSidecarsToReleaseDir() {
        val staging = createTempDir("apk-staging")
        val release = createTempDir("apk-release")
        File(staging, "app.apk").writeText("transformed")
        File(staging, "output-metadata.json").writeText("""{"variantName":"googleRelease"}""")
        File(staging, "baselineProfiles/0/app.dm").apply {
            parentFile.mkdirs()
            writeText("dm")
        }

        publishTransformedApks(staging, release)

        assertTrue(File(release, "app.apk").readText() == "transformed")
        assertTrue(File(release, "output-metadata.json").exists())
        assertTrue(File(release, "baselineProfiles/0/app.dm").exists())
    }

    @Test
    fun cleanStaleApkTransformArtifacts_removesKnownTempFiles() {
        val dir = createTempDir("apk-output")
        File(dir, "unsigned-app.apk").writeText("x")
        File(dir, "mapping-rewrite-123.apk").writeText("x")
        File(dir, "app-release.apk").writeText("apk")

        cleanStaleApkTransformArtifacts(dir)

        assertFalse(File(dir, "unsigned-app.apk").exists())
        assertFalse(File(dir, "mapping-rewrite-123.apk").exists())
        assertTrue(File(dir, "app-release.apk").exists())
    }

    @Test
    fun syncApkOutputMetadata_copiesPackageMetadata() {
        val input = createTempDir("apk-input")
        val output = createTempDir("apk-output")
        File(input, "output-metadata.json").writeText("""{"variantName":"googleRelease"}""")

        syncApkOutputMetadata(input, output)

        assertTrue(File(output, "output-metadata.json").readText().contains("googleRelease"))
    }

    @Test
    fun copyApkOutputSidecars_copiesBaselineProfilesOnly() {
        val input = createTempDir("apk-input")
        val output = createTempDir("apk-output")
        File(input, "app.apk").writeText("in")
        File(input, "output-metadata.json").writeText("{}")
        val profiles = File(input, "baselineProfiles/0/app.dm").apply {
            parentFile.mkdirs()
            writeText("dm")
        }

        copyApkOutputSidecars(input, output)

        assertTrue(File(output, "baselineProfiles/0/app.dm").exists())
        assertFalse(File(output, "output-metadata.json").exists())
        assertFalse(File(output, "app.apk").exists())
        assertFalse(profiles.readText().isEmpty())
    }
}
