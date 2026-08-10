package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.keep.AabResourceKeepVerifier
import io.github.amsonix.molt.internal.keep.ARTIFACT_VERIFY_BASELINE_HINT
import io.github.amsonix.molt.internal.keep.ApkResourceKeepVerifier
import io.github.amsonix.molt.internal.keep.KeepXmlParser
import io.github.amsonix.molt.internal.keep.MoltObfuscateArtifactVerify
import io.github.amsonix.molt.internal.resource.ImageAntiDetectVerifier
import org.gradle.api.logging.Logger
import java.io.File

/** APK/AAB Transform 验包逻辑（从 Transform Task 拆出，便于单测与维护）。 */
internal object MoltObfuscateTransformVerify {

    fun verifyBundleImageAntiDetect(
        taskName: String,
        logger: Logger,
        bundleFile: File,
        reportFile: File?,
        fail: Boolean,
    ) {
        val decodeResult = ImageAntiDetectVerifier.verifyBundleAllImagesDecodable(bundleFile)
        if (!decodeResult.success) {
            val message = buildString {
                append("$taskName: AAB image decode verify failed: ${decodeResult.message}")
                if (decodeResult.unchanged.isNotEmpty()) {
                    append(" paths=")
                    append(decodeResult.unchanged.take(5).joinToString())
                    if (decodeResult.unchanged.size > 5) append("...")
                }
            }
            if (fail) error(message) else logger.warn(message)
            if (fail) return
        } else {
            logger.lifecycle("$taskName: AAB image decode verify passed (${decodeResult.message})")
        }

        val report = reportFile?.takeIf { it.isFile }
        if (report == null) {
            val message = "$taskName: verifyBundleImageAntiDetect enabled but image-anti-detect-report missing"
            if (fail) error(message) else logger.warn(message)
            return
        }
        val processedMd5 = readProcessedMd5FromReport(report)
        if (processedMd5.isEmpty()) return
        val result = ImageAntiDetectVerifier.verifyBundleImageEntries(bundleFile, processedMd5)
        if (result.success) {
            logger.lifecycle("$taskName: AAB image anti-detect verify passed (${result.message})")
            return
        }
        val message = buildString {
            append("$taskName: AAB image anti-detect verify failed: ${result.message}")
            if (result.unchanged.isNotEmpty()) {
                append(" paths=")
                append(result.unchanged.take(5).joinToString())
                if (result.unchanged.size > 5) append("...")
            }
        }
        if (fail) error(message) else logger.warn(message)
    }

    fun verifyApkImageAntiDetect(
        taskName: String,
        logger: Logger,
        apkFile: File,
        reportFile: File?,
        fail: Boolean,
    ) {
        val decodeResult = ImageAntiDetectVerifier.verifyApkAllImagesDecodable(apkFile)
        if (!decodeResult.success) {
            val message = buildString {
                append("$taskName: APK image decode verify failed: ${decodeResult.message}")
                if (decodeResult.unchanged.isNotEmpty()) {
                    append(" paths=")
                    append(decodeResult.unchanged.take(5).joinToString())
                    if (decodeResult.unchanged.size > 5) append("...")
                }
            }
            if (fail) error(message) else logger.warn(message)
            if (fail) return
        } else {
            logger.lifecycle("$taskName: APK image decode verify passed (${decodeResult.message})")
        }

        val report = reportFile?.takeIf { it.isFile }
        if (report == null) {
            val message = "$taskName: verifyApkImageAntiDetect enabled but image-anti-detect-report missing"
            if (fail) error(message) else logger.warn(message)
            return
        }
        val processedMd5 = readProcessedMd5FromReport(report)
        if (processedMd5.isEmpty()) return
        val result = ImageAntiDetectVerifier.verifyApkImageEntries(apkFile, processedMd5)
        if (result.success) {
            logger.lifecycle("$taskName: APK image anti-detect verify passed (${result.message})")
            return
        }
        val message = buildString {
            append("$taskName: APK image anti-detect verify failed: ${result.message}")
            if (result.unchanged.isNotEmpty()) {
                append(" paths=")
                append(result.unchanged.take(5).joinToString())
                if (result.unchanged.size > 5) append("...")
            }
        }
        if (fail) error(message) else logger.warn(message)
    }

    fun verifyBundleKeep(
        taskName: String,
        logger: Logger,
        inputAab: File,
        outputAab: File,
        declaredKeepRules: List<KeepXmlParser.KeepResource>,
        useFirebaseBaseline: Boolean,
        failOnEmptyBaseline: Boolean,
        failOnMissingKeep: Boolean,
    ) {
        val inputTable = AabResourceKeepVerifier.readResourceTable(inputAab)
        val required = MoltObfuscateArtifactVerify.resolveRequiredPresentInTable(
            declaredKeepRules = declaredKeepRules,
            useFirebaseBaseline = useFirebaseBaseline,
            table = inputTable,
        )
        if (required.isEmpty()) {
            val message =
                "$taskName: verifyBundleKeep enabled but no artifact verify baseline " +
                    "($ARTIFACT_VERIFY_BASELINE_HINT)"
            if (failOnEmptyBaseline) {
                error(message)
            }
            logger.warn("$message; skip")
            return
        }
        val verification = AabResourceKeepVerifier.verify(
            aabFile = outputAab,
            required = required,
        )
        if (verification.success) {
            logger.lifecycle(
                "{}: AAB keep verification passed ({} required resources)",
                taskName,
                verification.present.size,
            )
            return
        }
        val message = "$taskName: AAB keep verification failed, kept resources renamed or removed: " +
            verification.missing.joinToString { resource -> resource.toQualifier() }
        if (failOnMissingKeep) {
            error(message)
        }
        logger.warn(message)
    }

    fun verifyApkKeep(
        taskName: String,
        logger: Logger,
        inputApk: File,
        outputApk: File,
        aapt2Executable: File,
        declaredKeepRules: List<KeepXmlParser.KeepResource>,
        useFirebaseBaseline: Boolean,
        failOnEmptyBaseline: Boolean,
        failOnMissingKeep: Boolean,
    ) {
        val inputTable = ApkResourceKeepVerifier.readResourceTable(inputApk, aapt2Executable)
        val required = MoltObfuscateArtifactVerify.resolveRequiredPresentInTable(
            declaredKeepRules = declaredKeepRules,
            useFirebaseBaseline = useFirebaseBaseline,
            table = inputTable,
        )
        if (required.isEmpty()) {
            val message =
                "$taskName: verifyApkKeep enabled but no artifact verify baseline " +
                    "($ARTIFACT_VERIFY_BASELINE_HINT)"
            if (failOnEmptyBaseline) {
                error(message)
            }
            logger.warn("$message; skip")
            return
        }
        val result = ApkResourceKeepVerifier.verify(
            apkFile = outputApk,
            aapt2Executable = aapt2Executable,
            required = required,
        )
        if (result.success) {
            logger.lifecycle(
                "$taskName: APK keep verify passed (${result.present.size} required resources present)",
            )
            return
        }
        val message = buildString {
            append("$taskName: APK keep verify failed, kept resources renamed or removed: ")
            append(result.missing.joinToString { it.toQualifier() })
        }
        if (failOnMissingKeep) {
            error(message)
        } else {
            logger.warn(message)
        }
    }

    /**
     * 将 transform 阶段图片 metadata 兜底注入记录追加到独立 report
     * （= overlay 共享 report 内容 + 本次注入记录），供 verifyApk/BundleImageAntiDetect 校验注入未丢失。
     */
    fun appendImagePatchRecords(
        overlayReport: File?,
        outputReport: File?,
        records: List<io.github.amsonix.molt.internal.resource.ImagePatchRecord>,
    ) {
        if (outputReport == null) return
        val lines = mutableListOf<String>()
        overlayReport?.takeIf { it.isFile }?.readLines()?.let { lines += it }
        records.forEach { record ->
            lines += "${record.entryName}\tPROCESSED\t${record.sourceMd5}\t${record.outputMd5}"
        }
        outputReport.parentFile?.mkdirs()
        outputReport.writeText(lines.joinToString("\n") + "\n")
    }

    private fun readProcessedMd5FromReport(report: File): Set<String> =
        report.readLines()
            .filter { it.contains('\t') && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size >= 4 && parts[1] == "PROCESSED") parts[3].takeIf { it.isNotBlank() } else null
            }
            .toSet()
}
