package io.github.amsonix.molt.internal.util

internal data class ResolvedVariantSettings(
    val resourceObfuscateEnabled: Boolean,
    val verifyApkKeep: Boolean,
    val verifyBundleKeep: Boolean,
    val bundleResourceObfuscateEnabled: Boolean,
    val obfuscateApk: Boolean,
    val componentRenameEnabled: Boolean,
    val viewRenameEnabled: Boolean,
    val stringEncryptEnabled: Boolean,
    val assetsProtectEnabled: Boolean,
    val dexPerturbEnabled: Boolean,
    val assetsEncryptEnabled: Boolean,
)

/** variantConfig 覆盖全局 resource / verify / bundle / rename 开关。 */
internal object VariantSettingsResolver {

    fun resolve(
        globalResourceObfuscateEnabled: Boolean,
        globalVerifyApkKeep: Boolean,
        globalVerifyBundleKeep: Boolean,
        globalBundleResourceObfuscateEnabled: Boolean,
        globalObfuscateApk: Boolean,
        globalComponentRenameEnabled: Boolean,
        globalViewRenameEnabled: Boolean,
        globalStringEncryptEnabled: Boolean,
        globalAssetsProtectEnabled: Boolean,
        globalDexPerturbEnabled: Boolean,
        globalAssetsEncryptEnabled: Boolean,
        variantResourceObfuscateEnabled: Boolean? = null,
        variantVerifyApkKeep: Boolean? = null,
        variantVerifyBundleKeep: Boolean? = null,
        variantBundleResourceObfuscateEnabled: Boolean? = null,
        variantObfuscateApk: Boolean? = null,
        variantComponentRenameEnabled: Boolean? = null,
        variantViewRenameEnabled: Boolean? = null,
        variantStringEncryptEnabled: Boolean? = null,
        variantAssetsProtectEnabled: Boolean? = null,
        variantDexPerturbEnabled: Boolean? = null,
        variantAssetsEncryptEnabled: Boolean? = null,
    ): ResolvedVariantSettings = ResolvedVariantSettings(
        resourceObfuscateEnabled = variantResourceObfuscateEnabled ?: globalResourceObfuscateEnabled,
        verifyApkKeep = variantVerifyApkKeep ?: globalVerifyApkKeep,
        verifyBundleKeep = variantVerifyBundleKeep ?: globalVerifyBundleKeep,
        bundleResourceObfuscateEnabled =
            variantBundleResourceObfuscateEnabled ?: globalBundleResourceObfuscateEnabled,
        obfuscateApk = variantObfuscateApk ?: globalObfuscateApk,
        componentRenameEnabled = variantComponentRenameEnabled ?: globalComponentRenameEnabled,
        viewRenameEnabled = variantViewRenameEnabled ?: globalViewRenameEnabled,
        stringEncryptEnabled = variantStringEncryptEnabled ?: globalStringEncryptEnabled,
        assetsProtectEnabled = variantAssetsProtectEnabled ?: globalAssetsProtectEnabled,
        dexPerturbEnabled = variantDexPerturbEnabled ?: globalDexPerturbEnabled,
        assetsEncryptEnabled = variantAssetsEncryptEnabled ?: globalAssetsEncryptEnabled,
    )
}
