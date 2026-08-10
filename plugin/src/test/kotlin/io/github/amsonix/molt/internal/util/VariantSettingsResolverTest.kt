package io.github.amsonix.molt.internal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantSettingsResolverTest {

    @Test
    fun resolve_usesGlobalDefaultsWhenNoVariantOverride() {
        val resolved = VariantSettingsResolver.resolve(
            globalResourceObfuscateEnabled = true,
            globalVerifyApkKeep = false,
            globalVerifyBundleKeep = true,
            globalBundleResourceObfuscateEnabled = true,
            globalObfuscateApk = true,
            globalComponentRenameEnabled = true,
            globalViewRenameEnabled = false,
            globalStringEncryptEnabled = true,
            globalAssetsProtectEnabled = false,
            globalDexPerturbEnabled = false,
            globalAssetsEncryptEnabled = false,
        )
        assertTrue(resolved.resourceObfuscateEnabled)
        assertFalse(resolved.verifyApkKeep)
        assertTrue(resolved.verifyBundleKeep)
        assertTrue(resolved.componentRenameEnabled)
        assertFalse(resolved.viewRenameEnabled)
        assertTrue(resolved.stringEncryptEnabled)
    }

    @Test
    fun resolve_variantOverrideWinsOverGlobal() {
        val resolved = VariantSettingsResolver.resolve(
            globalResourceObfuscateEnabled = true,
            globalVerifyApkKeep = true,
            globalVerifyBundleKeep = true,
            globalBundleResourceObfuscateEnabled = true,
            globalObfuscateApk = true,
            globalComponentRenameEnabled = true,
            globalViewRenameEnabled = true,
            globalStringEncryptEnabled = true,
            globalAssetsProtectEnabled = true,
            globalDexPerturbEnabled = true,
            globalAssetsEncryptEnabled = true,
            variantResourceObfuscateEnabled = false,
            variantVerifyApkKeep = false,
            variantObfuscateApk = false,
            variantComponentRenameEnabled = false,
            variantViewRenameEnabled = false,
            variantStringEncryptEnabled = false,
            variantAssetsProtectEnabled = false,
            variantDexPerturbEnabled = false,
            variantAssetsEncryptEnabled = false,
        )
        assertFalse(resolved.resourceObfuscateEnabled)
        assertFalse(resolved.verifyApkKeep)
        assertTrue(resolved.verifyBundleKeep)
        assertFalse(resolved.obfuscateApk)
        assertFalse(resolved.componentRenameEnabled)
        assertFalse(resolved.viewRenameEnabled)
        assertFalse(resolved.stringEncryptEnabled)
        assertFalse(resolved.assetsProtectEnabled)
    }
}
