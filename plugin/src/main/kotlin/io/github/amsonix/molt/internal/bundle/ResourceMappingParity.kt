package io.github.amsonix.molt.internal.bundle

import java.io.File

/**
 * 对比 APK/AAB 两份 resources-mapping.txt 的混淆规模，辅助发现双引擎 drift。
 *
 * 用法：
 * `./gradlew :build-logic:molt:moltObfuscateMappingParityCheck -Pvariant=googleRelease`
 * 或显式指定（路径相对 **仓库根目录** `<repo-root>/build/shell-obfuscate/...`）：
 * `./gradlew :build-logic:molt:moltObfuscateMappingParityCheck \
 *   -PapkMapping=build/shell-obfuscate/googleRelease/apk-resource/resources-mapping.txt \
 *   -PaabMapping=build/shell-obfuscate/googleRelease/bundle-resource/resources-mapping.txt`
 */
internal object ResourceMappingParity {

    data class Stats(
        val renamedDirs: Int,
        val renamedEntries: Int,
        val renamedPaths: Int,
    ) {
        val totalRenames: Int get() = renamedDirs + renamedEntries + renamedPaths
    }

    data class Result(
        val apk: Stats,
        val aab: Stats,
        val entryRatio: Double,
        val dirRatio: Double,
        val pathRatio: Double,
        /** 主门禁：res id mapping 规模（APK arsc vs AAB ResourceTable 可比）。 */
        val entryWithinTolerance: Boolean,
        /** 诊断项：dir/path 粒度因双引擎不同，仅 warn 不 fail。 */
        val dirPathWithinTolerance: Boolean,
    ) {
        val withinTolerance: Boolean get() = entryWithinTolerance
    }

    fun compare(apkMapping: File, aabMapping: File, tolerance: Double = 0.15): Result {
        require(apkMapping.isFile) { "APK mapping not found: ${apkMapping.path}" }
        require(aabMapping.isFile) { "AAB mapping not found: ${aabMapping.path}" }
        val apk = parseStats(apkMapping)
        val aab = parseStats(aabMapping)
        val entryRatio = ratioDiff(apk.renamedEntries, aab.renamedEntries)
        val dirRatio = ratioDiff(apk.renamedDirs, aab.renamedDirs)
        val pathRatio = ratioDiff(apk.renamedPaths, aab.renamedPaths)
        return Result(
            apk = apk,
            aab = aab,
            entryRatio = entryRatio,
            dirRatio = dirRatio,
            pathRatio = pathRatio,
            entryWithinTolerance = entryRatio <= tolerance,
            dirPathWithinTolerance = dirRatio <= tolerance && pathRatio <= tolerance,
        )
    }

    private fun ratioDiff(apkCount: Int, aabCount: Int): Double {
        val maxCount = maxOf(apkCount, aabCount, 1)
        return kotlin.math.abs(apkCount - aabCount).toDouble() / maxCount
    }

    fun parseStats(mappingFile: File): Stats {
        var section = Section.NONE
        var renamedDirs = 0
        var renamedEntries = 0
        var renamedPaths = 0
        mappingFile.forEachLine { line ->
            val trimmed = line.trim()
            when {
                trimmed == "res dir mapping:" -> section = Section.DIR
                trimmed == "res id mapping:" -> section = Section.ENTRY
                trimmed == "res entries path mapping:" -> section = Section.PATH
                trimmed.isEmpty() || !trimmed.contains("->") -> Unit
                section == Section.DIR -> renamedDirs++
                section == Section.ENTRY -> renamedEntries++
                section == Section.PATH -> renamedPaths++
            }
        }
        return Stats(
            renamedDirs = renamedDirs,
            renamedEntries = renamedEntries,
            renamedPaths = renamedPaths,
        )
    }

    private enum class Section {
        NONE,
        DIR,
        ENTRY,
        PATH,
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        error("Usage: ResourceMappingParityKt <apk-mapping.txt> <aab-mapping.txt>")
    }
    val result = ResourceMappingParity.compare(File(args[0]), File(args[1]))
    println(
        "APK mapping: dirs=${result.apk.renamedDirs} entries=${result.apk.renamedEntries} " +
            "paths=${result.apk.renamedPaths}",
    )
    println(
        "AAB mapping: dirs=${result.aab.renamedDirs} entries=${result.aab.renamedEntries} " +
            "paths=${result.aab.renamedPaths}",
    )
    println("entry rename ratio diff=${"%.2f".format(result.entryRatio * 100)}%")
    println("dir rename ratio diff=${"%.2f".format(result.dirRatio * 100)}% (diagnostic)")
    println("path rename ratio diff=${"%.2f".format(result.pathRatio * 100)}% (diagnostic)")
    if (!result.dirPathWithinTolerance) {
        println(
            "WARN: dir/path rename counts differ; APK zip-arsc vs AAB ResourceTable use " +
                "different mapping granularity (not a nightly failure)",
        )
    }
    check(result.entryWithinTolerance) {
        "APK/AAB entry rename count drift exceeds tolerance " +
            "(entry=${"%.2f".format(result.entryRatio * 100)}%)"
    }
    println("mapping parity check passed (entry gate)")
}
