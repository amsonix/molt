package io.github.amsonix.molt.internal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceObfuscateSettingsResolverTest {

    @Test
    fun resolve_usesGlobalWhenNoVariantOverride() {
        val resolved = ResourceObfuscateSettingsResolver.resolve(
            globalRenameXmlFiles = true,
            globalInjectXmlJunk = false,
            globalImageAntiDetect = true,
            globalImagePngMicroCompress = false,
            globalImageJpegMicroCompress = true,
            globalIncrementalOverlay = true,
        )
        assertTrue(resolved.renameXmlFiles)
        assertFalse(resolved.injectXmlJunk)
        assertTrue(resolved.imageAntiDetect)
    }

    @Test
    fun resolve_variantOverrideWinsOverGlobal() {
        val resolved = ResourceObfuscateSettingsResolver.resolve(
            globalRenameXmlFiles = true,
            globalInjectXmlJunk = true,
            globalImageAntiDetect = true,
            globalImagePngMicroCompress = true,
            globalImageJpegMicroCompress = true,
            globalIncrementalOverlay = true,
            variantRenameXmlFiles = false,
            variantImageAntiDetect = false,
            variantIncrementalOverlay = false,
        )
        assertFalse(resolved.renameXmlFiles)
        assertTrue(resolved.injectXmlJunk)
        assertFalse(resolved.imageAntiDetect)
        assertFalse(resolved.incrementalOverlay)
    }
}
