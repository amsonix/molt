package io.github.amsonix.molt.internal.bundle

import com.android.build.api.variant.ApplicationVariant
import io.github.amsonix.molt.internal.util.variantCapitalizedName
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File

/**
 * Gradle 8 会在配置期校验 ListingFileRedirectTask.listingFile 必须存在。
 * APK Transform 首次/失败后 outputs 下可能尚无 metadata，此处用 package 产物或占位文件兜底。
 */
internal object MoltObfuscateApkListingSeed {

    fun seedIfAbsent(project: Project, variant: ApplicationVariant) {
        val capitalized = variantCapitalizedName(variant.name)
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
    ): File {
        val flavor = extractFlavorSegment(variantName, buildType)
        val segments = listOfNotNull("outputs", "apk", flavor, buildType)
        return File(project.layout.buildDirectory.get().asFile, segments.joinToString("/"))
    }

    internal fun resolveListingMetadataFile(
        project: Project,
        variantName: String,
        buildType: String,
    ): File = File(resolveApkOutputDirectory(project, variantName, buildType), "output-metadata.json")

    internal fun resolveListingMetadataFile(project: Project, variant: ApplicationVariant): File =
        resolveListingMetadataFile(project, variant.name, variant.buildType ?: "release")

    /** AGP 8.13+ package 默认输出到 outputs/apk/{flavor}/{buildType}。 */
    internal fun resolvePackageOutputDirectory(
        project: Project,
        variantName: String,
        buildType: String,
    ): Provider<Directory> =
        project.layout.dir(
            project.provider {
                resolveApkOutputDirectory(project, variantName, buildType)
            },
        )

    internal fun resolvePackageMetadataFile(
        project: Project,
        variantName: String,
        buildType: String,
    ): File = File(
        resolveApkOutputDirectory(project, variantName, buildType),
        "output-metadata.json",
    )

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
