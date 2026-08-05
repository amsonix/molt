package io.github.amsonix.molt.internal.util

import io.github.amsonix.molt.MoltObfuscateExtension
import io.github.amsonix.molt.resolveSeed
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class MoltObfuscateSeedResolutionTest {

    @Test
    fun resolveSeed_prefersVariantConfigOverGlobal() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("molt", MoltObfuscateExtension::class.java)
        extension.seed.set(100)
        extension.variantConfig.create("googleRelease")
        extension.variantConfig.getByName("googleRelease").seed.set(42)
        assertEquals(42, extension.resolveSeed("googleRelease", "com.example.app"))
        assertEquals(100, extension.resolveSeed("samsungRelease", "com.example.app"))
    }

    @Test
    fun resolveSeed_fallsBackToApplicationIdHash() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("molt", MoltObfuscateExtension::class.java)
        val applicationId = "com.example.app"
        assertEquals(applicationId.hashCode(), extension.resolveSeed("googleRelease", applicationId))
    }
}
