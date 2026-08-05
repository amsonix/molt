package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

class MoltObfuscateApkListingSeedTest {

    @Test
    fun resolveApkOutputDirectory_googleRelease() {
        val project = projectWithBuildDir()
        val dir = MoltObfuscateApkListingSeed.resolveApkOutputDirectory(
            project,
            variantName = "googleRelease",
            buildType = "release",
        )
        assertTrue(dir.path.endsWith("outputs${File.separator}apk${File.separator}google${File.separator}release"))
    }

    @Test
    fun resolveApkOutputDirectory_alphaBuildTypeOnly() {
        val project = projectWithBuildDir()
        val dir = MoltObfuscateApkListingSeed.resolveApkOutputDirectory(
            project,
            variantName = "alpha",
            buildType = "alpha",
        )
        assertTrue(dir.path.endsWith("outputs${File.separator}apk${File.separator}alpha"))
    }

    @Test
    fun seedIfAbsent_writesPlaceholderMetadata() {
        val project = projectWithBuildDir()
        val metadata = MoltObfuscateApkListingSeed.resolveListingMetadataFile(
            project,
            variantName = "googleRelease",
            buildType = "release",
        )
        metadata.parentFile.mkdirs()

        MoltObfuscateApkListingSeed.seedIfAbsentFromPaths(
            project = project,
            variantName = "googleRelease",
            applicationId = "com.example.app",
            listingMetadata = metadata,
            packageMetadata = File(project.projectDir, "missing-package-metadata.json"),
        )

        assertTrue(metadata.isFile)
        assertEquals(
            "googleRelease",
            metadata.readText().substringAfter("\"variantName\": \"").substringBefore("\""),
        )
    }

    @Test
    fun resolvePackageOutputDirectory_googleRelease() {
        val project = projectWithBuildDir()
        val dir = MoltObfuscateApkListingSeed.resolvePackageOutputDirectory(
            project,
            variantName = "googleRelease",
            buildType = "release",
        ).get().asFile
        assertTrue(
            dir.path.endsWith(
                "outputs${File.separator}apk${File.separator}google${File.separator}release",
            ),
        )
    }

    @Test
    fun seedIfAbsent_refreshesStaleListingFromPackageMetadata() {
        val project = projectWithBuildDir()
        val listing = File(project.layout.buildDirectory.get().asFile, "outputs/apk/google/release/output-metadata.json")
        val packageMetadata = File(project.layout.buildDirectory.get().asFile, "package/output-metadata.json")
        listing.parentFile.mkdirs()
        packageMetadata.parentFile.mkdirs()
        listing.writeText("""{"variantName":"googleRelease","elements":[],"outputFile":"stale.apk"}""")
        Thread.sleep(5)
        packageMetadata.writeText("""{"variantName":"googleRelease","elements":[],"outputFile":"fresh.apk"}""")

        MoltObfuscateApkListingSeed.seedIfAbsentFromPaths(
            project = project,
            variantName = "googleRelease",
            applicationId = "com.example.app",
            listingMetadata = listing,
            packageMetadata = packageMetadata,
        )

        assertTrue(listing.readText().contains("fresh.apk"))
    }

    private fun projectWithBuildDir(): Project {
        val root = createTempDir("shell-apk-listing-seed")
        return ProjectBuilder.builder().withProjectDir(root).build()
    }
}
