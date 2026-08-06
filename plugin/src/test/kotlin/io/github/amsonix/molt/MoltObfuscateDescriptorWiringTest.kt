package io.github.amsonix.molt

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MoltObfuscateDescriptorWiringTest {

    @Test
    fun collectVariantSourceSetNames_includesMainFlavorAndVariantNames() {
        val names = MoltObfuscateDescriptorWiring.collectVariantSourceSetNames(
            variantName = "googleRelease",
            productFlavors = listOf("channel" to "google"),
            buildType = "release",
        )

        assertEquals(listOf("main", "google", "release", "googleRelease"), names)
    }

    @Test
    fun conventionalVariantResDirs_returnsExistingSrcResDirectories() {
        val project = ProjectBuilder.builder().build()
        File(project.projectDir, "src/main/res/layout").apply {
            mkdirs()
            resolve("base.xml").writeText("<FrameLayout />")
        }
        File(project.projectDir, "src/google/res/layout").apply {
            mkdirs()
            resolve("google.xml").writeText("<FrameLayout />")
        }

        val resolved = MoltObfuscateDescriptorWiring.conventionalVariantResDirs(
            project = project,
            sourceSetNames = listOf("main", "google", "release", "googleRelease"),
        ).map { it.invariantSeparatorsPath.substringAfter("src/") }

        assertEquals(listOf("main/res", "google/res"), resolved)
    }
}
