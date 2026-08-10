package io.github.amsonix.molt

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import io.github.amsonix.molt.internal.junk.JunkCodeGenerator
import io.github.amsonix.molt.internal.junk.JunkManifestMerger
import io.github.amsonix.molt.internal.keep.KeepXmlParser
import io.github.amsonix.molt.internal.resource.ImageAntiDetectVerifier
import io.github.amsonix.molt.internal.resource.ResourceObfuscator
import io.github.amsonix.molt.internal.resource.WebpExtendedSkipRatio
import io.github.amsonix.molt.internal.resource.parseJpegMetadataMode
import org.gradle.api.tasks.InputFile

@CacheableTask
abstract class MoltObfuscateJunkCodeTask : DefaultTask() {

    @get:Input
    abstract val seed: Property<Int>

    @get:Input
    abstract val packageCount: Property<Int>

    @get:Input
    abstract val classCount: Property<Int>

    @get:Input
    abstract val methodsPerClass: Property<Int>

    @get:Input
    abstract val activityCountPerPackage: Property<Int>

    @get:Input
    abstract val packagePrefix: Property<String>

    @get:Input
    abstract val excludeActivityJavaFile: Property<Boolean>

    @get:Input
    abstract val resPrefix: Property<String>

    @get:Input
    abstract val namespace: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        group = "molt"
    }

    @TaskAction
    fun generate() {
        JunkCodeGenerator.generate(
            outputDir = outputDirectory.get().asFile,
            config = JunkCodeGenerator.Config(
                packageCount = packageCount.get(),
                classCount = classCount.get(),
                methodsPerClass = methodsPerClass.get(),
                activityCountPerPackage = activityCountPerPackage.get(),
                excludeActivityJavaFile = excludeActivityJavaFile.get(),
                resPrefix = resPrefix.get(),
                namespace = namespace.get(),
                seed = seed.get(),
                packagePrefix = packagePrefix.get(),
            ),
        )
    }
}

abstract class MoltObfuscateMergeJunkManifestTask : DefaultTask() {

    @get:Input
    abstract val failOnMergeFailure: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val junkManifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    init {
        group = "molt"
        description = "Merge junk Activity declarations into merged manifest"
    }

    @TaskAction
    fun merge() {
        val junkSnippet = junkManifestFile.get().asFile.readText()
        val merged = mergedManifest.get().asFile.readText()
        val result = JunkManifestMerger.mergeIntoManifest(merged, junkSnippet)
        if (!result.merged) {
            val message =
                "molt: failed to merge junk activities: " +
                    (result.failureReason ?: "unknown reason")
            if (failOnMergeFailure.get()) {
                error(message)
            } else {
                logger.warn(message)
                updatedManifest.get().asFile.writeText(merged)
                return
            }
        }
        updatedManifest.get().asFile.writeText(result.manifest)
    }
}

@CacheableTask
abstract class MoltObfuscateGenerateJunkKeepTask : DefaultTask() {

    @get:Input
    abstract val junkEnabled: Property<Boolean>

    @get:Input
    abstract val packagePrefix: Property<String>

    @get:Input
    abstract val fogEnabled: Property<Boolean>

    @get:Input
    abstract val fogAssetsEnabled: Property<Boolean>

    /** 应用 applicationId 列表（wiring 按 variant 收集）——fog keep 规则按精确 appId 前缀生成。 */
    @get:Input
    abstract val applicationIds: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        group = "molt"
        description = "Generate R8 keep rules for shell-obfuscate junk classes"
    }

    @TaskAction
    fun generate() {
        val keepFile = outputFile.get().asFile
        keepFile.parentFile.mkdirs()
        keepFile.writeText(
            buildString {
                appendLine("# molt: generated junk keep rules")
                if (junkEnabled.get()) {
                    appendLine("-keep class ${packagePrefix.get()}.** { *; }")
                }
                if (fogEnabled.get()) {
                    // 类名由 seed 派生（每次构建不同）；规则按精确 applicationId 前缀生成，
                    // 避免 `**` 通配误伤用户自身含 shell.fog 段的类。
                    appendLine("# molt: generated fog keep rules")
                    applicationIds.get().distinct().forEach { appId ->
                        appendLine("-keep class $appId.shell.fog.* { *; }")
                    }
                }
                if (fogAssetsEnabled.get()) {
                    appendLine("# molt: generated fog-assets keep rules")
                    applicationIds.get().distinct().forEach { appId ->
                        appendLine("-keep class $appId.shell.fogassets.* { *; }")
                    }
                }
            },
        )
    }
}

@CacheableTask
abstract class MoltObfuscateMergeFogAssetsManifestTask : DefaultTask() {

    @get:Input
    abstract val providerSnippet: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    init {
        group = "molt"
        description = "Merge FogAssets ContentProvider into merged manifest"
    }

    @TaskAction
    fun merge() {
        val merged = mergedManifest.get().asFile.readText()
        val snippet = providerSnippet.get()
        val result = mergeProviderIntoManifest(merged, snippet)
        if (result == null) {
            error("molt: failed to merge FogAssets provider into merged manifest")
        }
        updatedManifest.get().asFile.writeText(result)
    }

    /** 把 snippet 中的 provider 节点并入 merged manifest 的 <application>。 */
    private fun mergeProviderIntoManifest(mergedManifest: String, providerSnippet: String): String? =
        runCatching {
            val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
            val mergedDoc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(mergedManifest)))
            val snippetDoc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(providerSnippet)))
            val application = mergedDoc.getElementsByTagName("application").item(0)
                ?: return@runCatching null
            val provider = snippetDoc.documentElement
            val imported = mergedDoc.importNode(provider, true)
            application.appendChild(imported)
            serialize(mergedDoc)
        }.getOrNull()

    private fun serialize(document: org.w3c.dom.Document): String {
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        val result = java.io.StringWriter()
        transformer.transform(
            javax.xml.transform.dom.DOMSource(document),
            javax.xml.transform.stream.StreamResult(result),
        )
        return result.toString()
    }
}

@CacheableTask
abstract class MoltObfuscateResourcesTask : DefaultTask() {

    @get:Input
    abstract val seed: Property<Int>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val renameXmlFiles: Property<Boolean>

    @get:Input
    abstract val injectXmlJunk: Property<Boolean>

    @get:Input
    abstract val imageAntiDetect: Property<Boolean>

    @get:Input
    abstract val imageMicroCompress: Property<Boolean>

    @get:Input
    abstract val imagePngMicroCompress: Property<Boolean>

    @get:Input
    abstract val imageJpegMicroCompress: Property<Boolean>

    @get:Input
    abstract val imageMicroCompressQuality: Property<Float>

    @get:Input
    abstract val imageJpegMetadataMode: Property<String>

    @get:Input
    abstract val imagePngExtraChunks: Property<Boolean>

    @get:Input
    abstract val imagePerceptualNoise: Property<Boolean>

    @get:Input
    abstract val verifyImageAntiDetect: Property<Boolean>

    @get:Input
    abstract val failOnUnchangedImageAntiDetect: Property<Boolean>

    @get:Input
    abstract val failOnSkippedUnsupportedImageAntiDetect: Property<Boolean>

    @get:Input
    abstract val incrementalOverlay: Property<Boolean>

    @get:Input
    abstract val overlayParallelism: Property<Int>

    @get:Input
    abstract val maxWebpExtendedSkipRatio: Property<Double>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val keepXmlFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputResDirs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val overlayCacheDirectory: DirectoryProperty

    @get:OutputFile
    abstract val xmlRenameReport: RegularFileProperty

    @get:OutputFile
    abstract val imageAntiDetectReport: RegularFileProperty

    init {
        group = "molt"
    }

    @TaskAction
    fun obfuscate() {
        val inputDirs = inputResDirs.files.filter { it.isDirectory }
        require(inputDirs.isNotEmpty()) {
            "molt: no res source directories for variant ${variantName.get()} (inputResDirs=${inputResDirs.files})"
        }
        val keepRules = KeepXmlParser.mergeKeepXmlFiles(keepXmlFiles.files)
        val metadataScope = "${applicationId.get()}/${variantName.get()}"
        val config = ResourceObfuscator.Config(
            seed = seed.get(),
            renameXmlFiles = renameXmlFiles.get(),
            injectXmlJunk = injectXmlJunk.get(),
            imageAntiDetect = imageAntiDetect.get(),
            imageMicroCompress = imageMicroCompress.get(),
            imagePngMicroCompress = imagePngMicroCompress.get(),
            imageJpegMicroCompress = imageJpegMicroCompress.get(),
            imageMicroCompressQuality = imageMicroCompressQuality.get(),
            imageJpegMetadataMode = parseJpegMetadataMode(imageJpegMetadataMode.get()),
            imagePngExtraChunks = imagePngExtraChunks.get(),
            imagePerceptualNoise = imagePerceptualNoise.get(),
            metadataScope = metadataScope,
        )
        val outRoot = outputDirectory.get().asFile
        val overlayCache = overlayCacheDirectory.get().asFile.takeIf { incrementalOverlay.get() }
        val result = ResourceObfuscator.obfuscateResTrees(
            inputResDirs = inputDirs,
            outputResDir = outRoot,
            keepRules = keepRules,
            config = config,
            overlayCacheDir = overlayCache,
            overlayLog = { message -> logger.lifecycle("$name: $message") },
            overlayParallelism = overlayParallelism.get(),
        )
        xmlRenameReport.get().asFile.writeText(
            buildString {
                appendLine("# xml rename mapping")
                result.xmlRenameMapping.forEach { (k, v) -> appendLine("$k -> $v") }
            },
        )
        val reportFile = imageAntiDetectReport.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            buildString {
                result.imageStats.toReportLines().forEach { appendLine(it) }
                appendLine("# records")
                result.imageRecords.forEach { record ->
                    appendLine(
                        "${record.relativePath}\t${record.outcome}\t${record.sourceMd5}\t${record.outputMd5.orEmpty()}",
                    )
                }
            },
        )
        val verifyResult = ImageAntiDetectVerifier.verifyOverlay(
            records = result.imageRecords,
            failOnUnchanged = failOnUnchangedImageAntiDetect.get(),
            failOnSkippedUnsupported = failOnSkippedUnsupportedImageAntiDetect.get(),
        )
        if (result.imageStats.processed + result.imageStats.skippedKeep +
            result.imageStats.skippedWebpExtended + result.imageStats.skippedUnsupported +
            result.imageStats.failed > 0
        ) {
            logger.lifecycle(
                "${name}: imageAntiDetect ${verifyResult.message}",
            )
        }
        if (verifyImageAntiDetect.get() && !verifyResult.success) {
            error("${name}: image anti-detect verify failed: ${verifyResult.message}")
        }
        if (result.imageStats.skippedWebpExtended > 0) {
            logger.warn(
                "${name}: skippedWebpExtended=${result.imageStats.skippedWebpExtended} " +
                    "(VP8+VP8L mixed inject failed; see image-anti-detect-report.txt)",
            )
        }
        val webpRatio = WebpExtendedSkipRatio.fromRecords(result.imageRecords)
        val maxWebpRatio = maxWebpExtendedSkipRatio.get()
        WebpExtendedSkipRatio.assertWithinThreshold(webpRatio, maxWebpRatio)?.let { violation ->
            error("${name}: $violation")
        }
        if (webpRatio.totalRecords > 0 && maxWebpRatio > 0.0) {
            logger.lifecycle("${name}: ${webpRatio.message} threshold=$maxWebpRatio")
        }
    }
}
