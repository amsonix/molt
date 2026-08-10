package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.keep.KeepXmlParser
import io.github.amsonix.molt.internal.util.normalizePath
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * 按 res 源目录做增量 overlay：未变目录复用缓存，仅重跑 fingerprint 变化的目录。
 * ponytail: fingerprint = 相对路径 + 文件内容 hash；目录未变则跳过重跑。
 */
internal object ResDirOverlayCache {

    private const val META_FILE = "overlay-meta.properties"
    private const val MAPPING_FILE = "overlay-mapping.properties"
    private const val RECORDS_FILE = "overlay-image-records.tsv"

    fun obfuscateIncremental(
        inputResDirs: List<File>,
        outputResDir: File,
        cacheRoot: File,
        keepRules: List<KeepXmlParser.KeepResource>,
        config: ResourceObfuscator.Config,
        log: (String) -> Unit = {},
    ): ResourceObfuscator.Result {
        val configHash = overlayConfigHash(config, keepRules)
        val dirsRoot = File(cacheRoot, "dirs").apply { mkdirs() }

        val sharedMapping = linkedMapOf<String, String>()
        val imageRecords = mutableListOf<ImageProcessRecord>()
        val byExt = mutableMapOf<String, Int>()
        val stagedResDirs = mutableListOf<File>()
        val parallelism = ResourceObfuscator.resolveOverlayParallelism(config.overlayParallelism)
        val processDir: (File) -> Unit = { inputDir ->
            val dirKey = dirKey(inputDir)
            val entryDir = File(dirsRoot, dirKey)
            val resOut = File(entryDir, "res")
            val metaFile = File(entryDir, META_FILE)
            val fingerprint = ResDirFingerprint.of(inputDir)

            val cacheHit = metaFile.isFile && resOut.isDirectory &&
                metaFile.loadProperties().let { meta ->
                    meta.getProperty("fingerprint") == fingerprint &&
                        meta.getProperty("configHash") == configHash
                }

            if (cacheHit) {
                log("overlay cache hit: ${inputDir.absolutePath}")
                synchronized(sharedMapping) {
                    loadMappingDelta(File(entryDir, MAPPING_FILE), sharedMapping)
                }
                synchronized(imageRecords) {
                    loadImageRecords(File(entryDir, RECORDS_FILE), imageRecords, byExt)
                }
                synchronized(stagedResDirs) {
                    stagedResDirs += resOut
                }
            } else {
                log("overlay cache miss: ${inputDir.absolutePath}")
                entryDir.deleteRecursively()
                resOut.mkdirs()
                val localMapping = linkedMapOf<String, String>()
                val localRecords = mutableListOf<ImageProcessRecord>()
                val localByExt = mutableMapOf<String, Int>()
                ResourceObfuscator.obfuscateSingleResDir(
                    inputResDir = inputDir,
                    outputResDir = resOut,
                    keepRules = keepRules,
                    config = config,
                    xmlRenameMapping = localMapping,
                    imageRecords = localRecords,
                    byExt = localByExt,
                )
                synchronized(sharedMapping) {
                    sharedMapping.putAll(localMapping)
                }
                synchronized(imageRecords) {
                    imageRecords.addAll(localRecords)
                    localByExt.forEach { (ext, count) ->
                        byExt[ext] = (byExt[ext] ?: 0) + count
                    }
                }
                saveMappingDelta(
                    file = File(entryDir, MAPPING_FILE),
                    mapping = localMapping,
                )
                saveImageRecords(
                    file = File(entryDir, RECORDS_FILE),
                    records = localRecords,
                )
                metaFile.parentFile.mkdirs()
                metaFile.outputStream().use { stream ->
                    Properties().apply {
                        setProperty("fingerprint", fingerprint)
                        setProperty("configHash", configHash)
                        setProperty("sourceDir", inputDir.absolutePath)
                    }.store(stream, "shell-obfuscate overlay cache")
                }
                synchronized(stagedResDirs) {
                    stagedResDirs += resOut
                }
            }
        }

        val directories = inputResDirs.filter(File::isDirectory)
        if (directories.size > 1 && parallelism > 1) {
            directories.parallelStream().forEach(processDir)
        } else {
            directories.forEach(processDir)
        }

        mergeResTrees(stagedResDirs, outputResDir)
        return ResourceObfuscator.Result(
            xmlRenameMapping = sharedMapping,
            imageStats = ResourceObfuscator.summarizeImageStats(imageRecords, byExt),
            imageRecords = imageRecords,
        )
    }

    internal fun overlayConfigHash(
        config: ResourceObfuscator.Config,
        keepRules: List<KeepXmlParser.KeepResource>,
    ): String {
        val keepPart = keepRules
            .sortedWith(compareBy({ it.type }, { it.name }))
            .joinToString(";") { "${it.type}:${it.name}" }
        val payload = buildString {
            append(config.seed).append('|')
            append(config.renameXmlFiles).append('|')
            append(config.injectXmlJunk).append('|')
            append(config.imageAntiDetect).append('|')
            append(config.imageMicroCompress).append('|')
            append(config.imagePngMicroCompress).append('|')
            append(config.imageJpegMicroCompress).append('|')
            append(config.imageMicroCompressQuality).append('|')
            append(config.imageJpegMetadataMode).append('|')
            append(config.imagePngExtraChunks).append('|')
            append(config.imagePerceptualNoise).append('|')
            append(config.metadataScope).append('|')
            append(config.overlayParallelism).append('|')
            append(keepPart)
        }
        return sha256Hex(payload)
    }

    internal fun dirKey(inputDir: File): String =
        sha256Hex(inputDir.canonicalFile.absolutePath).take(16)

    private fun mergeResTrees(sources: List<File>, target: File) {
        target.deleteRecursively()
        target.mkdirs()
        sources.forEach { source ->
            if (!source.isDirectory) return@forEach
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = normalizePath(file.relativeTo(source).path)
                val outFile = File(target, relative)
                outFile.parentFile.mkdirs()
                file.copyTo(outFile, overwrite = true)
            }
        }
    }

    private fun loadMappingDelta(file: File, target: MutableMap<String, String>) {
        if (!file.isFile) return
        file.loadProperties().forEach { (key, value) ->
            target[key.toString()] = value.toString()
        }
    }

    private fun saveMappingDelta(file: File, mapping: Map<String, String>) {
        file.parentFile?.mkdirs()
        Properties().apply {
            mapping.forEach { (key, value) -> setProperty(key, value) }
        }.store(file.outputStream(), "overlay mapping delta")
    }

    private fun loadImageRecords(
        file: File,
        target: MutableList<ImageProcessRecord>,
        byExt: MutableMap<String, Int>,
    ) {
        if (!file.isFile) return
        file.readLines().forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val parts = line.split('\t')
            if (parts.size < 4) return@forEach
            val record = ImageProcessRecord(
                relativePath = parts[0],
                sourceMd5 = parts[1],
                outputMd5 = parts[2].ifBlank { null },
                outcome = ImageOutcome.valueOf(parts[3]),
            )
            target += record
            if (record.outcome == ImageOutcome.PROCESSED) {
                val ext = ImageMetadataAntiDetectProcessor.resolveImageExt(record.relativePath)
                byExt[ext] = (byExt[ext] ?: 0) + 1
            }
        }
    }

    private fun saveImageRecords(file: File, records: List<ImageProcessRecord>) {
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine("# relativePath\tsourceMd5\toutputMd5\toutcome")
                records.forEach { record ->
                    appendLine(
                        "${record.relativePath}\t${record.sourceMd5}\t" +
                            "${record.outputMd5.orEmpty()}\t${record.outcome}",
                    )
                }
            },
        )
    }

    private fun File.loadProperties(): Properties =
        Properties().apply { if (isFile) inputStream().use(::load) }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(value.toByteArray())
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal object ResDirFingerprint {

    /** ≤256KiB 全量 hash；更大文件 head/middle/tail 抽样 + size。 */
    private const val FULL_HASH_MAX_BYTES = 256 * 1024L
    private const val SAMPLE_BYTES = 4096

    fun of(dir: File): String {
        if (!dir.isDirectory) return "empty"
        val digest = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { normalizePath(it.relativeTo(dir).path) }
            .forEach { file ->
                val relative = normalizePath(file.relativeTo(dir).path)
                digest.update(relative.toByteArray())
                if (file.length() <= FULL_HASH_MAX_BYTES) {
                    digest.update(file.readBytes())
                } else {
                    digest.update(sampleLargeFile(file))
                }
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sampleLargeFile(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val size = file.length()
        digest.update(longToBytes(size))
        // 均匀 8 段采样覆盖更多区域（3 段对中部内容变更不敏感，增量构建会输出陈旧图）。
        val segments = 8
        for (index in 0 until segments) {
            val start = (size * index) / segments
            val offset = if (index == segments - 1) size - SAMPLE_BYTES else start
            digestSampleAt(file, digest, offset = offset.coerceAtLeast(0L))
        }
        return digest.digest()
    }

    private fun digestSampleAt(file: File, digest: MessageDigest, offset: Long) {
        java.io.RandomAccessFile(file, "r").use { access ->
            access.seek(offset.coerceAtLeast(0L))
            val buffer = ByteArray(SAMPLE_BYTES)
            val read = access.read(buffer)
            if (read > 0) digest.update(buffer, 0, read)
        }
    }

    private fun longToBytes(value: Long): ByteArray =
        ByteArray(8) { index -> ((value ushr (index * 8)) and 0xFF).toByte() }
}
