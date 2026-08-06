package io.github.amsonix.molt.internal.bundle

import com.android.build.api.variant.ApplicationVariant
import io.github.amsonix.molt.internal.util.AgpToolchainCompatibility
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File

/**
 * Gradle 8 会在配置期校验 ListingFileRedirectTask.listingFile 必须存在。
 * APK Transform 首次/失败后 outputs 下可能尚无 metadata，此处用 package 产物或占位文件兜底。
 */
internal object MoltObfuscateApkListingSeed {

    /** AGP 8.7+ 使用 outputs/apk/{flavor}/{buildType}；更早版本用 outputs/apk/{variantName}。 */
    private const val MIN_AGP_FOR_SPLIT_APK_OUTPUT = "8.7.0"

    fun seedIfAbsent(project: Project, variant: ApplicationVariant) {
        val listingMetadata = resolveListingMetadataFile(project, variant)
        val packageMetadata = resolvePackageMetadataFile(
            project = project,
            variantName = variant.name,
            buildType = variant.buildType ?: "release",
        )
        seedIfAbsentFromPaths(
            project = project,
            variantName = variant.name,
            applicationId = variant.applicationId.get(),
            listingMetadata = listingMetadata,
            packageMetadata = packageMetadata,
        )
    }

    internal fun seedIfAbsentFromPaths(
        project: Project,
        variantName: String,
        applicationId: String,
        listingMetadata: File,
        packageMetadata: File,
    ) {
        listingMetadata.parentFile.mkdirs()
        if (packageMetadata.isFile) {
            if (!listingMetadata.isFile || packageMetadata.lastModified() > listingMetadata.lastModified()) {
                packageMetadata.copyTo(listingMetadata, overwrite = true)
                project.logger.info(
                    "molt: synced ${listingMetadata.name} for variant=$variantName " +
                        "from package output",
                )
            }
            return
        }
        if (listingMetadata.isFile) {
            return
        }
        listingMetadata.writeText(buildPlaceholderMetadata(variantName, applicationId))
        project.logger.info(
            "molt: seeded placeholder ${listingMetadata.name} for variant=$variantName",
        )
    }

    internal fun resolveApkOutputDirectory(project: Project, variant: ApplicationVariant): File =
        resolveApkOutputDirectory(project, variant.name, variant.buildType ?: "release")

    internal fun resolveApkOutputDirectory(
        project: Project,
        variantName: String,
        buildType: String,
    ): File = primaryApkOutputDirectory(project, variantName, buildType)

    internal fun resolveApkOutputDirectoryCandidates(
        project: Project,
        variantName: String,
        buildType: String,
    ): List<File> {
        val buildDir = project.layout.buildDirectory.get().asFile
        val flavor = extractFlavorSegment(variantName, buildType)
        return buildList {
            if (flavor != null) {
                add(File(buildDir, listOf("outputs", "apk", flavor, buildType).joinToString("/")))
            }
            add(File(buildDir, "outputs/apk/$variantName"))
            if (!variantName.equals(buildType, ignoreCase = true)) {
                add(File(buildDir, "outputs/apk/$buildType"))
            }
        }.distinctBy { it.absoluteFile.normalize().path }
    }

    internal fun resolveListingMetadataFile(
        project: Project,
        variantName: String,
        buildType: String,
    ): File = File(primaryApkOutputDirectory(project, variantName, buildType), "output-metadata.json")

    internal fun resolveListingMetadataFile(project: Project, variant: ApplicationVariant): File =
        resolveListingMetadataFile(project, variant.name, variant.buildType ?: "release")

    internal fun resolvePackageOutputDirectory(
        project: Project,
        variantName: String,
        buildType: String,
    ): Provider<Directory> =
        project.layout.dir(
            project.provider {
                primaryApkOutputDirectory(project, variantName, buildType)
            },
        )

    internal fun resolvePackageMetadataFile(
        project: Project,
        variantName: String,
        buildType: String,
    ): File {
        val metadataName = "output-metadata.json"
        resolveApkOutputDirectoryCandidates(project, variantName, buildType)
            .map { directory -> File(directory, metadataName) }
            .firstOrNull { candidate -> candidate.isFile }
            ?.let { return it }
        return File(primaryApkOutputDirectory(project, variantName, buildType), metadataName)
    }

    private fun primaryApkOutputDirectory(
        project: Project,
        variantName: String,
        buildType: String,
    ): File {
        val buildDir = project.layout.buildDirectory.get().asFile
        if (usesSplitApkOutputDirectory()) {
            val flavor = extractFlavorSegment(variantName, buildType)
            val segments = listOfNotNull("outputs", "apk", flavor, buildType)
            return File(buildDir, segments.joinToString("/"))
        }
        return File(buildDir, "outputs/apk/$variantName")
    }

    private fun usesSplitApkOutputDirectory(): Boolean {
        val agpVersion = AgpToolchainCompatibility.readAgpVersion() ?: return true
        return AgpToolchainCompatibility.isAgpAtLeast(agpVersion, MIN_AGP_FOR_SPLIT_APK_OUTPUT)
    }

    private fun extractFlavorSegment(variantName: String, buildType: String): String? {
        if (variantName.equals(buildType, ignoreCase = true)) {
            return null
        }
        if (!variantName.endsWith(buildType, ignoreCase = true)) {
            return null
        }
        val flavor = variantName.dropLast(buildType.length)
        return flavor.takeIf { it.isNotEmpty() }
    }

    private fun buildPlaceholderMetadata(variantName: String, applicationId: String): String =
        """
            {
              "version": 3,
              "artifactType": {
                "type": "APK",
                "kind": "Directory"
              },
              "applicationId": "$applicationId",
              "variantName": "$variantName",
              "elements": [],
              "elementType": "File"
            }
            """.trimIndent()
}
