package io.github.amsonix.molt.internal.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MoltObfuscateDefaultsTest {

    @Test
    fun junkPackagePrefix_appendsShellJunkSuffix() {
        assertEquals("com.myapp.shell.junk", MoltObfuscateDefaults.junkPackagePrefix("com.myapp"))
    }

    @Test
    fun projectPackagePrefixes_normalizesTrailingDot() {
        assertEquals(listOf("com.myapp."), MoltObfuscateDefaults.projectPackagePrefixes("com.myapp"))
    }

    @Test
    fun normalizePackagePrefix_addsTrailingDot() {
        assertEquals("com.myapp.", MoltObfuscateDefaults.normalizePackagePrefix("com.myapp"))
        assertEquals("com.myapp.", MoltObfuscateDefaults.normalizePackagePrefix("com.myapp."))
    }

    @Test
    fun shrinkKeepRelativePath_replacesVariantToken() {
        assertEquals(
            "generated/shrink-resources/googleRelease/res/raw/keep.xml",
            MoltObfuscateDefaults.shrinkKeepRelativePath(
                variantName = "googleRelease",
                pattern = "generated/shrink-resources/{variant}/res/raw/keep.xml",
            ),
        )
    }
}
