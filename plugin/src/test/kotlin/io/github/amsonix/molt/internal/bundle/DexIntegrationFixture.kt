package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.util.variantCapitalizedName
import java.io.File

internal object DexIntegrationFixture {

    private const val APK_PROPERTY = "molt.integrationApk"
    private const val VARIANT_PROPERTY = "molt.integrationVariant"

    fun integrationVariant(): String =
        System.getProperty(VARIANT_PROPERTY)?.takeIf(String::isNotBlank) ?: "googleRelease"

    fun outputRoot(root: File): File {
        val sample = sampleRoot(root)
        return if (sample != null) {
            File(sample, "build/shell-obfuscate")
        } else {
            File(root, "build/shell-obfuscate")
        }
    }

    private fun sampleRoot(root: File): File? = when {
        File(root, "sample/app").isDirectory -> File(root, "sample")
        File(root, "sample/app").isDirectory -> File(root, "sample")
        else -> null
    }

    fun componentMapping(root: File, variant: String = integrationVariant()): File =
        File(outputRoot(root), "$variant/component-mapping.json")

    fun viewMapping(root: File, variant: String = integrationVariant()): File =
        File(outputRoot(root), "$variant/view-mapping.json")

    fun r8Mapping(root: File, variant: String = integrationVariant()): File {
        sampleRoot(root)?.let { sample ->
            return File(sample, "app/build/outputs/mapping/$variant/mapping.txt")
        }
        return File(root, "app/build/outputs/mapping/$variant/mapping.txt")
    }

    fun apkCandidate(projectRoot: File, explicitPath: String? = System.getProperty(APK_PROPERTY)): File {
        explicitPath?.takeIf(String::isNotBlank)?.let { path ->
            return File(path).let { if (it.isAbsolute) it else File(projectRoot, path) }
        }
        val variant = integrationVariant()
        val apkDirs = buildList {
            sampleRoot(projectRoot)?.let { sample ->
                add(File(sample, "app/build/outputs/apk/google/release"))
                add(File(sample, "app/build/intermediates/apk/$variant/package${variantCapitalizedName(variant)}"))
            }
            add(File(projectRoot, "app/build/outputs/apk/google/release"))
            add(
                File(
                    projectRoot,
                    "app/build/intermediates/apk/$variant/package${variantCapitalizedName(variant)}",
                ),
            )
        }
        for (releaseDir in apkDirs) {
            releaseDir.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile &&
                        file.name.endsWith(".apk") &&
                        !file.name.startsWith("mapping-rewrite-")
                }
                .maxWithOrNull(compareBy<File>({ it.lastModified() }, { it.name }))
                ?.let { return it }
        }
        return File(projectRoot, "app/build/outputs/apk/google/release/app-google-release.apk")
    }

    fun apk(projectRoot: File, explicitPath: String? = System.getProperty(APK_PROPERTY)): File {
        val candidate = apkCandidate(projectRoot, explicitPath)
        require(candidate.isFile) {
            "integration APK missing: ${candidate.path}; " +
                "run :plugin:moltObfuscateSampleAssemble first or set -PintegrationApk=<path>"
        }
        return candidate
    }
}
