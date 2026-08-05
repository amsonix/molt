package io.github.amsonix.molt.internal.junk

/** Junk 量级 preset，对齐 AndroidJunkCode 膨胀档位。 */
internal enum class JunkCodeProfile(
    val packageCount: Int,
    val classCount: Int,
    val methodsPerClass: Int,
    val activityCountPerPackage: Int,
) {
    LIGHT(5, 30, 8, 0),
    MEDIUM(10, 100, 12, 0),
    HEAVY(30, 1500, 20, 0),
    ;

    companion object {
        fun parse(name: String): JunkCodeProfile? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

internal data class ResolvedJunkConfig(
    val enabled: Boolean,
    val packageCount: Int,
    val classCount: Int,
    val methodsPerClass: Int,
    val activityCountPerPackage: Int,
    val excludeActivityJavaFile: Boolean,
    val mergeJunkManifest: Boolean,
    val resPrefix: String,
    val packagePrefix: String,
)

internal data class JunkCountPreset(
    val packageCount: Int,
    val classCount: Int,
    val methodsPerClass: Int,
    val activityCountPerPackage: Int,
)

internal object JunkConfigResolver {

    fun resolve(
        globalEnabled: Boolean,
        globalProfile: String,
        globalPackageCount: Int,
        globalClassCount: Int,
        globalMethodsPerClass: Int,
        globalActivityCountPerPackage: Int,
        globalExcludeActivityJavaFile: Boolean,
        globalMergeJunkManifest: Boolean,
        globalResPrefix: String,
        globalPackagePrefix: String,
        variantProfile: String? = null,
        variantEnabled: Boolean? = null,
        variantPackageCount: Int? = null,
        variantClassCount: Int? = null,
        variantMethodsPerClass: Int? = null,
        variantActivityCountPerPackage: Int? = null,
        variantExcludeActivityJavaFile: Boolean? = null,
        variantMergeJunkManifest: Boolean? = null,
        variantResPrefix: String? = null,
    ): ResolvedJunkConfig {
        val profileName = variantProfile?.takeIf { it.isNotBlank() }
            ?: globalProfile.takeIf { it.isNotBlank() }
            ?: "light"
        val preset = resolvePreset(
            profileName = profileName,
            fallbackPackageCount = variantPackageCount ?: globalPackageCount,
            fallbackClassCount = variantClassCount ?: globalClassCount,
            fallbackMethodsPerClass = variantMethodsPerClass ?: globalMethodsPerClass,
            fallbackActivityCountPerPackage = variantActivityCountPerPackage ?: globalActivityCountPerPackage,
        )
        return ResolvedJunkConfig(
            enabled = variantEnabled ?: globalEnabled,
            packageCount = variantPackageCount ?: preset.packageCount,
            classCount = variantClassCount ?: preset.classCount,
            methodsPerClass = variantMethodsPerClass ?: preset.methodsPerClass,
            activityCountPerPackage = variantActivityCountPerPackage ?: preset.activityCountPerPackage,
            excludeActivityJavaFile = variantExcludeActivityJavaFile ?: globalExcludeActivityJavaFile,
            mergeJunkManifest = variantMergeJunkManifest ?: globalMergeJunkManifest,
            resPrefix = variantResPrefix?.takeIf { it.isNotBlank() } ?: globalResPrefix,
            packagePrefix = globalPackagePrefix,
        )
    }

    private fun resolvePreset(
        profileName: String,
        fallbackPackageCount: Int,
        fallbackClassCount: Int,
        fallbackMethodsPerClass: Int,
        fallbackActivityCountPerPackage: Int,
    ): JunkCountPreset {
        if (profileName.equals("custom", ignoreCase = true)) {
            return JunkCountPreset(
                packageCount = fallbackPackageCount,
                classCount = fallbackClassCount,
                methodsPerClass = fallbackMethodsPerClass,
                activityCountPerPackage = fallbackActivityCountPerPackage,
            )
        }
        val profile = JunkCodeProfile.parse(profileName) ?: JunkCodeProfile.LIGHT
        return JunkCountPreset(
            packageCount = profile.packageCount,
            classCount = profile.classCount,
            methodsPerClass = profile.methodsPerClass,
            activityCountPerPackage = profile.activityCountPerPackage,
        )
    }
}
