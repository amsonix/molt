package io.github.amsonix.molt.internal.junk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JunkConfigResolverTest {

    @Test
    fun resolve_lightProfileUsesPresetCounts() {
        val resolved = JunkConfigResolver.resolve(
            globalEnabled = true,
            globalProfile = "light",
            globalPackageCount = 99,
            globalClassCount = 99,
            globalMethodsPerClass = 99,
            globalActivityCountPerPackage = 99,
            globalExcludeActivityJavaFile = false,
            globalMergeJunkManifest = false,
            globalResPrefix = "junk_",
            globalPackagePrefix = "com.example.junk",
        )
        assertEquals(5, resolved.packageCount)
        assertEquals(30, resolved.classCount)
        assertEquals(8, resolved.methodsPerClass)
        assertEquals(0, resolved.activityCountPerPackage)
    }

    @Test
    fun resolve_heavyProfileMatchesAndroidJunkCodeScale() {
        val resolved = JunkConfigResolver.resolve(
            globalEnabled = true,
            globalProfile = "heavy",
            globalPackageCount = 5,
            globalClassCount = 30,
            globalMethodsPerClass = 8,
            globalActivityCountPerPackage = 0,
            globalExcludeActivityJavaFile = false,
            globalMergeJunkManifest = false,
            globalResPrefix = "junk_",
            globalPackagePrefix = "com.example.junk",
        )
        assertEquals(30, resolved.packageCount)
        assertEquals(1500, resolved.classCount)
        assertEquals(20, resolved.methodsPerClass)
    }

    @Test
    fun resolve_variantOverrideWinsOverGlobalProfile() {
        val resolved = JunkConfigResolver.resolve(
            globalEnabled = true,
            globalProfile = "heavy",
            globalPackageCount = 5,
            globalClassCount = 30,
            globalMethodsPerClass = 8,
            globalActivityCountPerPackage = 0,
            globalExcludeActivityJavaFile = false,
            globalMergeJunkManifest = false,
            globalResPrefix = "junk_",
            globalPackagePrefix = "com.example.junk",
            variantProfile = "light",
        )
        assertEquals(5, resolved.packageCount)
        assertEquals(30, resolved.classCount)
    }

    @Test
    fun resolve_customProfileUsesExplicitCounts() {
        val resolved = JunkConfigResolver.resolve(
            globalEnabled = true,
            globalProfile = "custom",
            globalPackageCount = 7,
            globalClassCount = 11,
            globalMethodsPerClass = 3,
            globalActivityCountPerPackage = 2,
            globalExcludeActivityJavaFile = true,
            globalMergeJunkManifest = true,
            globalResPrefix = "shell_",
            globalPackagePrefix = "com.example.junk",
        )
        assertEquals(7, resolved.packageCount)
        assertEquals(11, resolved.classCount)
        assertTrue(resolved.excludeActivityJavaFile)
        assertTrue(resolved.mergeJunkManifest)
        assertEquals("shell_", resolved.resPrefix)
    }
}
