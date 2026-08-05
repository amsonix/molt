package io.github.amsonix.molt.internal.resource

import java.io.File
import java.util.zip.ZipFile

internal object ImageAntiDetectVerifier {

    data class VerifyResult(
        val success: Boolean,
        val unchanged: List<String>,
        val message: String,
    )

    fun verifyOverlay(
        records: List<ImageProcessRecord>,
        failOnUnchanged: Boolean,
        failOnSkippedUnsupported: Boolean = false,
    ): VerifyResult {
        val unchanged = records.filter {
            it.outcome == ImageOutcome.PROCESSED &&
                it.sourceMd5.equals(it.outputMd5, ignoreCase = true)
        }.map { it.relativePath }
        val failed = records.filter { it.outcome == ImageOutcome.FAILED }.map { it.relativePath }
        val skippedUnsupported = records.filter {
            it.outcome == ImageOutcome.SKIPPED_UNSUPPORTED
        }.map { it.relativePath }
        val bad = buildList {
            if (failOnUnchanged) addAll(unchanged)
            addAll(failed)
            if (failOnSkippedUnsupported) addAll(skippedUnsupported)
        }
        val success = bad.isEmpty()
        val message = buildString {
            append("processed=${records.count { it.outcome == ImageOutcome.PROCESSED }}")
            append(" skippedKeep=${records.count { it.outcome == ImageOutcome.SKIPPED_KEEP }}")
            append(" skippedWebpExtended=${records.count { it.outcome == ImageOutcome.SKIPPED_WEBP_EXTENDED }}")
            append(" skippedUnsupported=${skippedUnsupported.size}")
            append(" unchanged=${unchanged.size}")
            append(" failed=${failed.size}")
            if (bad.isNotEmpty()) {
                append(" paths=")
                append(bad.take(5).joinToString())
                if (bad.size > 5) append("...")
            }
        }
        return VerifyResult(
            success = success,
            unchanged = bad,
            message = message,
        )
    }

    /** 校验 APK 内 res/ 下全部图片 entry 可被 Android 解码。 */
    fun verifyApkAllImagesDecodable(apkFile: File): VerifyResult =
        verifyZipAllImagesDecodable(apkFile, artifactLabel = "apk")

    /** 校验 AAB/APKS zip 内全部 res 图片 entry 可被 Android 解码。 */
    fun verifyBundleAllImagesDecodable(bundleFile: File): VerifyResult =
        verifyZipAllImagesDecodable(bundleFile, artifactLabel = "aab")

    private fun verifyZipAllImagesDecodable(zipFile: File, artifactLabel: String): VerifyResult {
        if (!zipFile.isFile) {
            return VerifyResult(false, emptyList(), "$artifactLabel missing: ${zipFile.path}")
        }
        val undecodable = mutableListOf<String>()
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && isResImageEntry(it.name) && isImageEntry(it.name) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    val fileName = entry.name.substringAfterLast('/')
                    if (!ImageDecodeVerifier.verifyDecodable(bytes, fileName)) {
                        undecodable += entry.name
                    }
                }
        }
        return VerifyResult(
            success = undecodable.isEmpty(),
            unchanged = undecodable,
            message = "${artifactLabel}ImagesDecodable=${undecodable.isEmpty()} undecodable=${undecodable.size}",
        )
    }

    fun verifyBundleImageEntries(
        bundleFile: File,
        expectedProcessedMd5: Set<String>,
    ): VerifyResult = verifyZipImageEntries(bundleFile, expectedProcessedMd5, "aab")

    private fun verifyZipImageEntries(
        zipFile: File,
        expectedProcessedMd5: Set<String>,
        artifactLabel: String,
    ): VerifyResult {
        if (!zipFile.isFile) {
            return VerifyResult(false, emptyList(), "$artifactLabel missing: ${zipFile.path}")
        }
        val found = mutableSetOf<String>()
        val undecodable = mutableListOf<String>()
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && isResImageEntry(it.name) && isImageEntry(it.name) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    val md5 = ImageMetadataAntiDetectProcessor.md5Hex(bytes)
                    if (md5 !in expectedProcessedMd5) return@forEach
                    found += md5
                    val fileName = entry.name.substringAfterLast('/')
                    if (!ImageDecodeVerifier.verifyDecodable(bytes, fileName)) {
                        undecodable += entry.name
                    }
                }
        }
        val missing = expectedProcessedMd5 - found
        val bad = missing.toList() + undecodable
        val success = bad.isEmpty() || (expectedProcessedMd5.isEmpty() && undecodable.isEmpty())
        return VerifyResult(
            success = success,
            unchanged = bad,
            message = buildString {
                append("${artifactLabel}ImageMatched=${found.size}/${expectedProcessedMd5.size}")
                append(" undecodable=${undecodable.size}")
            },
        )
    }

    private fun isResImageEntry(name: String): Boolean =
        name.startsWith("res/") || name.contains("/res/")

    fun verifyApkImageEntries(
        apkFile: File,
        expectedProcessedMd5: Set<String>,
    ): VerifyResult {
        if (!apkFile.isFile) {
            return VerifyResult(false, emptyList(), "apk missing: ${apkFile.path}")
        }
        val found = mutableSetOf<String>()
        val undecodable = mutableListOf<String>()
        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && isImageEntry(it.name) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    val md5 = ImageMetadataAntiDetectProcessor.md5Hex(bytes)
                    if (md5 !in expectedProcessedMd5) return@forEach
                    found += md5
                    val fileName = entry.name.substringAfterLast('/')
                    if (!ImageDecodeVerifier.verifyDecodable(bytes, fileName)) {
                        undecodable += entry.name
                    }
                }
        }
        val missing = expectedProcessedMd5 - found
        val bad = missing.toList() + undecodable
        val success = bad.isEmpty() || (expectedProcessedMd5.isEmpty() && undecodable.isEmpty())
        return VerifyResult(
            success = success,
            unchanged = bad,
            message = buildString {
                append("apkImageMatched=${found.size}/${expectedProcessedMd5.size}")
                append(" undecodable=${undecodable.size}")
            },
        )
    }

    private fun isImageEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".9.png")
    }
}

internal enum class ImageOutcome {
    PROCESSED,
    SKIPPED_KEEP,
    /** VP8+VP8L 混合等无法安全注入的 WebP。 */
    SKIPPED_WEBP_EXTENDED,
    SKIPPED_UNSUPPORTED,
    FAILED,
}

internal data class ImageProcessRecord(
    val relativePath: String,
    val sourceMd5: String,
    val outputMd5: String?,
    val outcome: ImageOutcome,
)

internal data class ImageStats(
    val processed: Int = 0,
    val skippedKeep: Int = 0,
    val skippedWebpExtended: Int = 0,
    val skippedUnsupported: Int = 0,
    val failed: Int = 0,
    val byExt: Map<String, Int> = emptyMap(),
) {
    fun toReportLines(): List<String> = listOf(
        "processed=$processed",
        "skippedKeep=$skippedKeep",
        "skippedWebpExtended=$skippedWebpExtended",
        "skippedUnsupported=$skippedUnsupported",
        "failed=$failed",
        "byExt=$byExt",
    )
}
