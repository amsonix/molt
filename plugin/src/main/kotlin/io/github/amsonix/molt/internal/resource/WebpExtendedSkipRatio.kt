package io.github.amsonix.molt.internal.resource

/** overlay 报表 WebP extended skip 占比校验（与 nightly verify 脚本逻辑一致）。 */
internal object WebpExtendedSkipRatio {

    data class Result(
        val totalRecords: Int,
        val skippedWebpExtended: Int,
        val ratio: Double,
    ) {
        val message: String
            get() = "webpExtendedSkipRatio=${"%.4f".format(ratio)} ($skippedWebpExtended/$totalRecords)"
    }

    fun fromRecords(records: List<ImageProcessRecord>): Result {
        val total = records.size
        val skipped = records.count { it.outcome == ImageOutcome.SKIPPED_WEBP_EXTENDED }
        val ratio = if (total == 0) 0.0 else skipped.toDouble() / total
        return Result(totalRecords = total, skippedWebpExtended = skipped, ratio = ratio)
    }

    fun fromReportLines(lines: List<String>): Result {
        val dataLines = lines.filter { line ->
            line.isNotBlank() && !line.startsWith("#") && '\t' in line
        }
        val skipped = dataLines.count { line ->
            line.split('\t').getOrNull(1) == ImageOutcome.SKIPPED_WEBP_EXTENDED.name
        }
        val total = dataLines.size
        val ratio = if (total == 0) 0.0 else skipped.toDouble() / total
        return Result(totalRecords = total, skippedWebpExtended = skipped, ratio = ratio)
    }

    fun assertWithinThreshold(result: Result, maxRatio: Double): String? {
        if (maxRatio <= 0.0 || result.totalRecords == 0) return null
        return if (result.ratio > maxRatio) {
            "${result.message} exceeds max=$maxRatio"
        } else {
            null
        }
    }
}
