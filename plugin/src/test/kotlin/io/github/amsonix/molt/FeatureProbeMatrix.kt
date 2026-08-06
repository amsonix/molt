package io.github.amsonix.molt

import java.io.File

/** 解析 `tools/feature-probe-matrix.txt` 并解析环境变量选行。 */
object FeatureProbeMatrix {

    enum class ProbeType {
        SMOKE,
        APK,
        AAB,
        APK_RENAME,
        AAB_RENAME,
        ALL,
        /** 真实 sample 工程；由 `feature-probe.sh` / `moltObfuscateSampleAssemble` 执行。 */
        SAMPLE,
        /** 宿主 integration root；由 `feature-probe.sh` / `moltObfuscateIntegrationPrepare` 执行。 */
        INTEGRATION,
        ;

        companion object {
            fun parse(raw: String): ProbeType = when (raw.trim()) {
                "smoke" -> SMOKE
                "apk" -> APK
                "aab" -> AAB
                "apk-rename" -> APK_RENAME
                "aab-rename" -> AAB_RENAME
                "all" -> ALL
                "sample" -> SAMPLE
                "integration" -> INTEGRATION
                else -> error("Unknown feature probe type: $raw")
            }

            fun requiresTransformE2e(type: ProbeType): Boolean =
                type !in setOf(SMOKE, SAMPLE, INTEGRATION)

            /** TestKit 不执行；须走 shell runner 或对应 Gradle Exec 任务。 */
            fun isShellOnly(type: ProbeType): Boolean =
                type in setOf(SAMPLE, INTEGRATION)
        }
    }

    data class Row(
        val featureId: String,
        val preset: String,
        val probe: ProbeType,
        val agp: String,
        val gradle: String,
        val tier: String,
        val required: Boolean,
    )

    fun loadMatrix(repoRoot: File): List<Row> {
        val matrixFile = File(repoRoot, "tools/feature-probe-matrix.txt")
        check(matrixFile.isFile) { "Missing feature probe matrix: ${matrixFile.absolutePath}" }
        return matrixFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("""\s+"""))
                check(parts.size >= 7) { "Invalid feature probe matrix row: $line" }
                Row(
                    featureId = parts[0],
                    preset = parts[1],
                    probe = ProbeType.parse(parts[2]),
                    agp = parts[3],
                    gradle = parts[4],
                    tier = parts[5],
                    required = parts[6] == "1",
                )
            }
    }

    fun resolveRow(): Row {
        val featureId = sequenceOf(
            System.getProperty("MOLT_FEATURE_PROBE"),
            System.getenv("MOLT_FEATURE_PROBE"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?: error("Set MOLT_FEATURE_PROBE or -PmoltFeature=<feature_id>")

        val repoRoot = sequenceOf(
            System.getProperty("MOLT_REPO_ROOT"),
            System.getenv("MOLT_REPO_ROOT"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.let(::File)
            ?: error("MOLT_REPO_ROOT is not set")

        val presetOverride = sequenceOf(
            System.getProperty("MOLT_FEATURE_PRESET"),
            System.getenv("MOLT_FEATURE_PRESET"),
        ).firstOrNull { !it.isNullOrBlank() }

        val agp = sequenceOf(
            System.getProperty("MOLT_TEST_AGP"),
            System.getenv("MOLT_TEST_AGP"),
        ).firstOrNull { !it.isNullOrBlank() }

        val gradle = sequenceOf(
            System.getProperty("MOLT_TEST_GRADLE"),
            System.getenv("MOLT_TEST_GRADLE"),
        ).firstOrNull { !it.isNullOrBlank() }

        val tierFilter = sequenceOf(
            System.getProperty("FEATURE_PROBE_TIER"),
            System.getenv("FEATURE_PROBE_TIER"),
        ).firstOrNull { !it.isNullOrBlank() && !it.equals("all", ignoreCase = true) }

        val candidates = loadMatrix(repoRoot).filter { row ->
            row.featureId == featureId &&
                (tierFilter == null || row.tier == tierFilter)
        }

        val resolved = when {
            agp != null && gradle != null -> {
                candidates.firstOrNull { it.agp == agp && it.gradle == gradle }
                    ?: candidates.firstOrNull()
            }
            candidates.size == 1 -> candidates.single()
            else -> candidates.firstOrNull()
        } ?: error(
            "Ambiguous or missing feature probe row for $featureId " +
                "(agp=$agp gradle=$gradle tier=$tierFilter; candidates=${candidates.size})",
        )

        return if (presetOverride.isNullOrBlank()) {
            resolved
        } else {
            resolved.copy(preset = presetOverride)
        }
    }
}
