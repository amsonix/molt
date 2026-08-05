package io.github.amsonix.molt.internal.util

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLibraryDependencyGraphTest {

    @Test
    fun resolveLibraryProjects_returnsEmptyForStandaloneProject() {
        val project = ProjectBuilder.builder().build()
        val libraries = AppLibraryDependencyGraph.resolveLibraryProjects(project, listOf("release"))
        assertTrue(libraries.isEmpty())
    }

    @Test
    fun classpathConfigurationNames_usesCompleteFlavorVariantName() {
        assertEquals(
            listOf("googleReleaseRuntimeClasspath", "googleReleaseCompileClasspath"),
            AppLibraryDependencyGraph.classpathConfigurationNames("googleRelease"),
        )
    }
}
