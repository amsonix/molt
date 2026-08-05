package io.github.amsonix.molt.internal.util

internal data class ResolvedVariantSettings(
    val resourceObfuscateEnabled: Boolean,
    val verifyApkKeep: Boolean,
    val verifyBundleKeep: Boolean,
    val bundleResourceObfuscateEnabled: Boolean,
    val obfuscateApk: Boolean,
    val componentRenameEnabled: Boolean,
    val viewRenameEnabled: Boolean,
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
        variantResourceObfuscateEnabled: Boolean? = null,
        variantVerifyApkKeep: Boolean? = null,
        variantVerifyBundleKeep: Boolean? = null,
        variantBundleResourceObfuscateEnabled: Boolean? = null,
        variantObfuscateApk: Boolean? = null,
        variantComponentRenameEnabled: Boolean? = null,
        variantViewRenameEnabled: Boolean? = null,
    ): ResolvedVariantSettings = ResolvedVariantSettings(
        resourceObfuscateEnabled = variantResourceObfuscateEnabled ?: globalResourceObfuscateEnabled,
        verifyApkKeep = variantVerifyApkKeep ?: globalVerifyApkKeep,
        verifyBundleKeep = variantVerifyBundleKeep ?: globalVerifyBundleKeep,
        bundleResourceObfuscateEnabled =
            variantBundleResourceObfuscateEnabled ?: globalBundleResourceObfuscateEnabled,
        obfuscateApk = variantObfuscateApk ?: globalObfuscateApk,
        componentRenameEnabled = variantComponentRenameEnabled ?: globalComponentRenameEnabled,
        viewRenameEnabled = variantViewRenameEnabled ?: globalViewRenameEnabled,
    )
}
