package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.keep.KeepFilter
import io.github.amsonix.molt.internal.keep.KeepXmlParser
import io.github.amsonix.molt.internal.util.ObfuscateNaming
import io.github.amsonix.molt.internal.util.SeedRandom
import io.github.amsonix.molt.internal.util.normalizePath
import java.io.File
import java.util.Random

internal object ResourceObfuscator {

    data class Config(
        val seed: Int,
        val renameXmlFiles: Boolean,
        val injectXmlJunk: Boolean,
        val imageAntiDetect: Boolean,
        /** master 开关；false 时 PNG/JPEG 微压缩均关闭。 */
        val imageMicroCompress: Boolean = true,
        val imagePngMicroCompress: Boolean = false,
        val imageJpegMicroCompress: Boolean = true,
        val imageMicroCompressQuality: Float = 0.97f,
        val imageJpegMetadataMode: ImageMetadataAntiDetectProcessor.JpegMetadataMode =
            ImageMetadataAntiDetectProcessor.JpegMetadataMode.BOTH,
        val imagePngExtraChunks: Boolean = true,
        val imagePerceptualNoise: Boolean = false,
        val metadataScope: String = "",
        val overlayParallelism: Int = 0,
    )

    data class Result(
        val xmlRenameMapping: Map<String, String>,
        val imageStats: ImageStats,
        val imageRecords: List<ImageProcessRecord>,
    ) {
        val processedImageCount: Int get() = imageStats.processed
        val skippedImageCount: Int
            get() = imageStats.skippedKeep + imageStats.skippedWebpExtended +
                imageStats.skippedUnsupported + imageStats.failed
    }

    fun obfuscateResTree(
        inputResDir: File,
        outputResDir: File,
        keepRules: List<KeepXmlParser.KeepResource>,
        config: Config,
    ): Result = obfuscateResTrees(listOf(inputResDir), outputResDir, keepRules, config)

    fun obfuscateResTrees(
        inputResDirs: Iterable<File>,
        outputResDir: File,
        keepRules: List<KeepXmlParser.KeepResource>,
        config: Config,
        overlayCacheDir: File? = null,
        overlayLog: (String) -> Unit = {},
        overlayParallelism: Int = 0,
    ): Result {
        val dirs = inputResDirs.filter { it.isDirectory }.toList()
        if (overlayCacheDir != null) {
            return ResDirOverlayCache.obfuscateIncremental(
                inputResDirs = dirs,
                outputResDir = outputResDir,
                cacheRoot = overlayCacheDir,
                keepRules = keepRules,
                config = config.copy(overlayParallelism = overlayParallelism),
                log = overlayLog,
            )
        }
        outputResDir.deleteRecursively()
        outputResDir.mkdirs()

        val xmlRenameMapping = linkedMapOf<String, String>()
        val imageRecords = mutableListOf<ImageProcessRecord>()
        val byExt = mutableMapOf<String, Int>()
        dirs.forEach { inputResDir ->
            obfuscateSingleResDir(
                inputResDir = inputResDir,
                outputResDir = outputResDir,
                keepRules = keepRules,
                config = config,
                xmlRenameMapping = xmlRenameMapping,
                imageRecords = imageRecords,
                byExt = byExt,
            )
        }
        return Result(
            xmlRenameMapping = xmlRenameMapping,
            imageStats = summarizeImageStats(imageRecords, byExt),
            imageRecords = imageRecords,
        )
    }

    internal fun obfuscateSingleResDir(
        inputResDir: File,
        outputResDir: File,
        keepRules: List<KeepXmlParser.KeepResource>,
        config: Config,
        xmlRenameMapping: MutableMap<String, String>,
        imageRecords: MutableList<ImageProcessRecord>,
        byExt: MutableMap<String, Int>,
    ) {
        val junkRandom = SeedRandom.create(config.seed, "resource-obfuscate")
        val allFiles = inputResDir.walkTopDown().filter { it.isFile }.toList()
        val parallelism = resolveOverlayParallelism(config.overlayParallelism)
        val imageFiles = allFiles.filter { file ->
            val relativePath = normalizePath(file.relativeTo(inputResDir).path)
            isImagePath(relativePath) && config.imageAntiDetect &&
                KeepFilter.shouldObfuscate(
                    resourceTypeFromPath(relativePath) ?: return@filter false,
                    resourceNameFromPath(relativePath),
                    keepRules,
                )
        }
        val nonImageFiles = allFiles.filter { it !in imageFiles.toSet() }
        nonImageFiles.forEach { file ->
            processResourceFile(
                file = file,
                inputResDir = inputResDir,
                outputResDir = outputResDir,
                keepRules = keepRules,
                config = config,
                junkRandom = junkRandom,
                xmlRenameMapping = xmlRenameMapping,
                imageRecords = imageRecords,
                byExt = byExt,
            )
        }
        val processImage: (File) -> Unit = { file ->
            processResourceFile(
                file = file,
                inputResDir = inputResDir,
                outputResDir = outputResDir,
                keepRules = keepRules,
                config = config,
                junkRandom = junkRandom,
                xmlRenameMapping = xmlRenameMapping,
                imageRecords = imageRecords,
                byExt = byExt,
            )
        }
        if (imageFiles.size > 1 && parallelism > 1) {
            imageFiles.parallelStream().forEach { file -> processImage(file) }
        } else {
            imageFiles.forEach(processImage)
        }
    }

    private fun processResourceFile(
        file: File,
        inputResDir: File,
        outputResDir: File,
        keepRules: List<KeepXmlParser.KeepResource>,
        config: Config,
        junkRandom: Random,
        xmlRenameMapping: MutableMap<String, String>,
        imageRecords: MutableList<ImageProcessRecord>,
        byExt: MutableMap<String, Int>,
    ) {
        val relativePath = normalizePath(file.relativeTo(inputResDir).path)
        val resourceType = resourceTypeFromPath(relativePath) ?: run {
            copyRaw(file, outputResDir, relativePath)
            return
        }
        val resourceName = resourceNameFromPath(relativePath)
        if (!KeepFilter.shouldObfuscate(resourceType, resourceName, keepRules)) {
            copyRaw(file, outputResDir, relativePath)
            if (isImagePath(relativePath)) {
                synchronized(imageRecords) {
                    recordImage(
                        imageRecords,
                        relativePath,
                        file,
                        null,
                        ImageOutcome.SKIPPED_KEEP,
                    )
                }
            }
            return
        }

        when {
            relativePath.endsWith(".xml") -> {
                val newRelative =
                    maybeRenameXml(relativePath, resourceType, resourceName, config, xmlRenameMapping)
                val outFile = File(outputResDir, newRelative)
                outFile.parentFile.mkdirs()
                var content = file.readText()
                if (config.injectXmlJunk && resourceType == "layout") {
                    content = XmlJunkInjector.inject(content, junkRandom)
                }
                outFile.writeText(content)
            }
            isImagePath(relativePath) && config.imageAntiDetect -> {
                val outFile = File(outputResDir, relativePath)
                outFile.parentFile.mkdirs()
                val sourceBytes = file.readBytes()
                val sourceMd5 = ImageMetadataAntiDetectProcessor.md5Hex(sourceBytes)
                val ext = ImageMetadataAntiDetectProcessor.resolveImageExt(file.name)
                val token = buildMetadataToken(config.metadataScope, relativePath, config.seed)
                val processConfig = buildProcessConfig(config, token)
                val ok = ImageMetadataAntiDetectProcessor.process(
                    input = file,
                    output = outFile,
                    random = SeedRandom.create(config.seed, "image-$relativePath"),
                    config = processConfig,
                )
                synchronized(imageRecords) {
                    if (ok) {
                        val outputMd5 = ImageMetadataAntiDetectProcessor.md5Hex(outFile.readBytes())
                        recordImage(
                            imageRecords,
                            relativePath,
                            sourceMd5,
                            outputMd5,
                            ImageOutcome.PROCESSED,
                        )
                        synchronized(byExt) {
                            byExt[ext] = (byExt[ext] ?: 0) + 1
                        }
                    } else {
                        file.copyTo(outFile, overwrite = true)
                        val outcome = if (
                            ext == "webp" && ImageMetadataAntiDetectProcessor.isIntentionalWebpSkip(sourceBytes)
                        ) {
                            ImageOutcome.SKIPPED_WEBP_EXTENDED
                        } else {
                            ImageOutcome.SKIPPED_UNSUPPORTED
                        }
                        recordImage(
                            imageRecords,
                            relativePath,
                            sourceMd5,
                            ImageMetadataAntiDetectProcessor.md5Hex(outFile.readBytes()),
                            outcome,
                        )
                    }
                }
            }
            else -> copyRaw(file, outputResDir, relativePath)
        }
    }

    internal fun resolveOverlayParallelism(requested: Int): Int =
        if (requested > 0) {
            requested
        } else {
            minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        }

    internal fun buildMetadataToken(metadataScope: String, relativePath: String, seed: Int): String {
        val pathHash = relativePath.hashCode().toUInt().toString(16)
        return if (metadataScope.isBlank()) {
            "seed=$seed/path=$pathHash"
        } else {
            "$metadataScope/seed=$seed/path=$pathHash"
        }
    }

    internal fun summarizeImageStats(
        records: List<ImageProcessRecord>,
        byExt: Map<String, Int>,
    ): ImageStats = ImageStats(
        processed = records.count { it.outcome == ImageOutcome.PROCESSED },
        skippedKeep = records.count { it.outcome == ImageOutcome.SKIPPED_KEEP },
        skippedWebpExtended = records.count { it.outcome == ImageOutcome.SKIPPED_WEBP_EXTENDED },
        skippedUnsupported = records.count { it.outcome == ImageOutcome.SKIPPED_UNSUPPORTED },
        failed = records.count { it.outcome == ImageOutcome.FAILED },
        byExt = byExt,
    )

    private fun buildProcessConfig(
        config: Config,
        token: String,
    ): ImageMetadataAntiDetectProcessor.ProcessConfig {
        val master = config.imageMicroCompress
        return ImageMetadataAntiDetectProcessor.ProcessConfig(
            pngMicroCompress = master && config.imagePngMicroCompress,
            jpegMicroCompress = master && config.imageJpegMicroCompress,
            microCompressQuality = config.imageMicroCompressQuality,
            jpegMetadataMode = config.imageJpegMetadataMode,
            pngExtraChunks = config.imagePngExtraChunks,
            metadataToken = token,
            perceptualNoise = config.imagePerceptualNoise,
        )
    }

    private fun recordImage(
        records: MutableList<ImageProcessRecord>,
        relativePath: String,
        sourceFile: File,
        outputMd5: String?,
        outcome: ImageOutcome,
    ) {
        recordImage(
            records,
            relativePath,
            ImageMetadataAntiDetectProcessor.md5Hex(sourceFile.readBytes()),
            outputMd5,
            outcome,
        )
    }

    private fun recordImage(
        records: MutableList<ImageProcessRecord>,
        relativePath: String,
        sourceMd5: String,
        outputMd5: String?,
        outcome: ImageOutcome,
    ) {
        records += ImageProcessRecord(
            relativePath = relativePath,
            sourceMd5 = sourceMd5,
            outputMd5 = outputMd5,
            outcome = outcome,
        )
    }

    private fun maybeRenameXml(
        relativePath: String,
        resourceType: String,
        resourceName: String,
        config: Config,
        mapping: MutableMap<String, String>,
    ): String {
        if (!config.renameXmlFiles) return relativePath
        if (resourceType !in RENAMABLE_XML_TYPES) return relativePath
        val mappingKey = "$resourceType/$resourceName"
        val newName = mapping.getOrPut(mappingKey) {
            ObfuscateNaming.nextResourceName(
                SeedRandom.create(config.seed, "xml-$mappingKey"),
                resourceType.first(),
            )
        }
        return relativePath.replaceAfterLast('/', "$newName.xml")
    }

    internal fun resourceTypeFromPath(relativePath: String): String? {
        val type = relativePath.substringBefore('/').substringBefore('-')
        return type.takeIf { it in SUPPORTED_RESOURCE_TYPES }
    }

    private fun resourceNameFromPath(relativePath: String): String =
        relativePath.substringAfterLast('/').substringBeforeLast('.')

    private fun isImagePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".9.png")
    }

    private fun copyRaw(file: File, outputResDir: File, relativePath: String) {
        val outFile = File(outputResDir, relativePath)
        outFile.parentFile.mkdirs()
        file.copyTo(outFile, overwrite = true)
    }

    private val SUPPORTED_RESOURCE_TYPES =
        setOf("layout", "xml", "drawable", "mipmap", "menu", "anim", "animator", "transition")
    private val RENAMABLE_XML_TYPES = setOf("layout", "xml", "menu", "anim", "animator", "transition")
}

internal object XmlJunkInjector {

    fun inject(xml: String, random: Random): String {
        if (!xml.contains("<")) return xml
        val junkComment = "\n    <!-- shell-obfuscate-junk:${random.nextInt()} -->"
        val insertAt = xml.lastIndexOf("</")
        if (insertAt <= 0) return xml + junkComment
        return xml.substring(0, insertAt) + junkComment + xml.substring(insertAt)
    }
}
