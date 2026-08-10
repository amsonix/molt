package io.github.amsonix.molt.internal.junk

import io.github.amsonix.molt.internal.util.ObfuscateNaming
import io.github.amsonix.molt.internal.util.SeedRandom
import java.io.File
import java.util.Random

internal object JunkCodeGenerator {

    data class Config(
        /** 子包数量；utility 类会均分到各子包。 */
        val packageCount: Int,
        /** utility 类总数（不含 Activity）。 */
        val classCount: Int,
        val methodsPerClass: Int,
        /** 每个子包生成的 Activity 数；默认 0。 */
        val activityCountPerPackage: Int,
        /** 仅生成 layout/Manifest，跳过 Activity .java（对齐 AJC excludeActivityJavaFile）。 */
        val excludeActivityJavaFile: Boolean = false,
        val resPrefix: String = "junk_",
        val namespace: String = "",
        val seed: Int,
        val packagePrefix: String,
    )

    data class Result(
        val utilityClassCount: Int,
        val activityClassCount: Int,
        val layoutCount: Int,
        val packageNames: List<String>,
        val activityClassNames: List<String>,
    )

    fun generate(outputDir: File, config: Config): Result {
        require(config.packageCount > 0) { "packageCount must be > 0" }
        require(config.classCount >= 0) { "classCount must be >= 0" }
        require(config.methodsPerClass >= 0) { "methodsPerClass must be >= 0" }
        require(config.activityCountPerPackage >= 0) { "activityCountPerPackage must be >= 0" }

        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val javaDir = File(outputDir, "java").apply { mkdirs() }
        val resDir = File(outputDir, "res").apply { mkdirs() }
        val layoutDir = File(resDir, "layout").apply { mkdirs() }

        val random = SeedRandom.create(config.seed, "junk-code")
        val utilityPerPackage = distributeCount(config.classCount, config.packageCount)
        var utilityClassCount = 0
        var activityClassCount = 0
        var layoutCount = 0
        val packageNames = mutableListOf<String>()
        val activityClassNames = mutableListOf<String>()

        repeat(config.packageCount) { packageIndex ->
            val subPackage = ObfuscateNaming.nextClassName(
                SeedRandom.create(config.seed, "junk-pkg-$packageIndex"),
            )
            val packageName = "${config.packagePrefix}.$subPackage"
            packageNames += packageName
            val packageDir = File(javaDir, packageName.replace('.', '/')).apply { mkdirs() }

            repeat(utilityPerPackage[packageIndex]) { classIndex ->
                val className = "Junk${
                    ObfuscateNaming.nextResourceName(random, 'J').replaceFirstChar { it.uppercaseChar() }
                }"
                File(packageDir, "$className.java").writeText(
                    buildUtilityClassSource(
                        packageName = packageName,
                        className = className,
                        methodCount = config.methodsPerClass,
                        random = random,
                        classIndex = classIndex,
                    ),
                )
                utilityClassCount++
            }

            repeat(config.activityCountPerPackage) { activityIndex ->
                val activityStem = ObfuscateNaming.nextResourceName(random, 'a')
                val className = "${activityStem.replaceFirstChar { it.uppercaseChar() }}Activity"
                val layoutName = buildLayoutName(config.resPrefix, packageName, activityStem)
                val layoutFile = File(layoutDir, "$layoutName.xml")
                layoutFile.writeText(JunkActivityLayoutTemplates.layoutXml(rootId = "root_$activityStem"))
                layoutCount++

                val fqcn = "$packageName.$className"
                activityClassNames += fqcn

                if (!config.excludeActivityJavaFile) {
                    File(packageDir, "$className.java").writeText(
                        buildActivityClassSource(
                            packageName = packageName,
                            className = className,
                            layoutName = layoutName,
                            namespace = config.namespace,
                            methodCount = config.methodsPerClass.coerceAtMost(4),
                            random = random,
                            activityIndex = activityIndex,
                            mergeManifest = true,
                        ),
                    )
                    activityClassCount++
                }
            }
        }

        File(outputDir, "AndroidManifest.xml").writeText(
            JunkManifestMerger.buildManifestSnippet(activityClassNames),
        )

        return Result(
            utilityClassCount = utilityClassCount,
            activityClassCount = activityClassCount,
            layoutCount = layoutCount,
            packageNames = packageNames,
            activityClassNames = activityClassNames,
        )
    }

    internal fun distributeCount(total: Int, buckets: Int): IntArray {
        if (buckets <= 0) return intArrayOf()
        if (total <= 0) return IntArray(buckets)
        val base = total / buckets
        val remainder = total % buckets
        return IntArray(buckets) { index -> base + if (index < remainder) 1 else 0 }
    }

    private fun buildLayoutName(resPrefix: String, packageName: String, activityStem: String): String {
        val prefix = resPrefix.lowercase().trimEnd('_') + "_"
        val pkgToken = packageName.replace('.', '_').lowercase()
        return "${prefix}${pkgToken}_activity_$activityStem"
    }

    private fun buildUtilityClassSource(
        packageName: String,
        className: String,
        methodCount: Int,
        random: Random,
        classIndex: Int,
    ): String = buildString {
        appendLine("package $packageName;")
        appendLine()
        appendLine("/** Generated junk utility class for anti-similarity detection. */")
        appendLine("@SuppressWarnings(\"unused\")")
        appendLine("public final class $className {")
        val salt = random.nextInt()
        appendLine("    private static final String TAG = \"${className}_${classIndex}_${random.nextInt(9999)}\";")
        appendLine("    private static final int SALT = $salt;")
        appendLine()
        repeat(methodCount) { methodIndex ->
            val methodName = "m${methodIndex}_${random.nextInt(1000)}"
            JunkMethodBodyTemplates.appendUtilityMethod(this, "    ", methodName, random, salt)
        }
        appendLine("    private $className() {}")
        appendLine("}")
    }

    private fun buildActivityClassSource(
        packageName: String,
        className: String,
        layoutName: String,
        namespace: String,
        methodCount: Int,
        random: Random,
        activityIndex: Int,
        mergeManifest: Boolean,
    ): String = buildString {
        appendLine("package $packageName;")
        appendLine()
        appendLine("import android.app.Activity;")
        appendLine("import android.os.Bundle;")
        if (namespace.isNotBlank()) {
            appendLine("import $namespace.R;")
        }
        appendLine()
        val manifestNote = if (mergeManifest) {
            "Manifest merge enabled when molt.junkCode.mergeJunkManifest=true."
        } else {
            "not merged into Manifest by default."
        }
        appendLine("/** Generated junk Activity ($manifestNote) */")
        appendLine("@SuppressWarnings(\"unused\")")
        appendLine("public final class $className extends Activity {")
        appendLine("    private static final String TAG = \"${className}_$activityIndex\";")
        appendLine()
        appendLine("    @Override")
        appendLine("    protected void onCreate(Bundle savedInstanceState) {")
        appendLine("        super.onCreate(savedInstanceState);")
        if (namespace.isNotBlank()) {
            appendLine("        setContentView(R.layout.$layoutName);")
        }
        appendLine("    }")
        appendLine()
        repeat(methodCount) { methodIndex ->
            val methodName = "onExtra${methodIndex}_${random.nextInt(1000)}"
            JunkMethodBodyTemplates.appendInstanceVoidMethod(this, "    ", methodName, random)
        }
        appendLine("}")
    }
}
