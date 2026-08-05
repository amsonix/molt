package io.github.amsonix.molt.internal.resource

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32

/**
 * 方案 A + 可选微压缩 + 可选感知扰动：
 * - PNG：tEXt / iTXt / ancillary chunk（默认不走 ImageIO）
 * - JPEG：EXIF APP1 + COM
 * - WebP：简单 VP8 wrap VP8X + XMP；已有 VP8X 最小侵入 XMP；纯 VP8L 追加 sObf 私有 chunk
 */
internal object ImageMetadataAntiDetectProcessor {

    enum class JpegMetadataMode { COM, EXIF, BOTH }

    data class ProcessConfig(
        val pngMicroCompress: Boolean = false,
        val jpegMicroCompress: Boolean = true,
        val microCompressQuality: Float = 0.97f,
        val jpegMetadataMode: JpegMetadataMode = JpegMetadataMode.BOTH,
        val pngExtraChunks: Boolean = true,
        val metadataToken: String = "",
        val perceptualNoise: Boolean = false,
    )

    fun process(
        input: File,
        output: File,
        random: Random,
        config: ProcessConfig = ProcessConfig(),
    ): Boolean {
        val ext = resolveImageExt(input.name)
        var bytes = input.readBytes()
        if (config.perceptualNoise) {
            bytes = applyPerceptualNoise(bytes, ext, random) ?: bytes
        }
        if (config.pngMicroCompress && ext == "png") {
            bytes = microCompressRaster(bytes, "png", config.microCompressQuality, random) ?: bytes
        }
        if (config.jpegMicroCompress && ext in JPEG_EXTENSIONS) {
            bytes = microCompressRaster(bytes, ext, config.microCompressQuality, random) ?: bytes
        }
        val token = config.metadataToken.ifBlank { "obfuscate-${random.nextInt()}" }
        if (hasShellMetadata(bytes, input.name)) {
            output.parentFile?.mkdirs()
            output.writeBytes(bytes)
            return true
        }
        val updated = when (ext) {
            "png" -> injectPngMetadata(bytes, random, token, config.pngExtraChunks)
            in JPEG_EXTENSIONS -> injectJpegMetadata(bytes, token, config.jpegMetadataMode)
            "webp" -> injectWebpMetadata(bytes, random, token, input.name)
            else -> null
        } ?: return false
        if (!ImageDecodeVerifier.verifyDecodable(updated, input.name)) return false
        output.parentFile?.mkdirs()
        output.writeBytes(updated)
        return true
    }

    fun resolveImageExt(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".9.png") -> "png"
            lower.endsWith(".png") -> "png"
            lower.endsWith(".jpeg") -> "jpeg"
            lower.endsWith(".jpg") -> "jpg"
            lower.endsWith(".webp") -> "webp"
            else -> lower.substringAfterLast('.', "")
        }
    }

    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun applyPerceptualNoise(bytes: ByteArray, ext: String, random: Random): ByteArray? = runCatching {
        if (ext !in NOISE_EXTENSIONS) return null
        val image = javax.imageio.ImageIO.read(bytes.inputStream()) ?: return null
        val pixelCount = image.width * image.height
        if (pixelCount <= 0) return null
        val touchCount = maxOf(1, pixelCount / 1000)
        repeat(touchCount) {
            val x = random.nextInt(image.width)
            val y = random.nextInt(image.height)
            val rgb = image.getRGB(x, y)
            val channel = random.nextInt(3)
            val shift = channel * 8
            val mask = 0xFF shl shift
            val value = (rgb shr shift) and 0xFF
            val updated = (rgb and mask.inv()) or (((value xor 1) and 0xFF) shl shift)
            image.setRGB(x, y, updated)
        }
        val format = if (ext == "png") "png" else "jpeg"
        ByteArrayOutputStream().also { buffer ->
            check(javax.imageio.ImageIO.write(image, format, buffer))
        }.toByteArray()
    }.getOrNull()

    private fun microCompressRaster(
        input: ByteArray,
        ext: String,
        quality: Float,
        random: Random,
    ): ByteArray? = runCatching {
        val image = javax.imageio.ImageIO.read(input.inputStream()) ?: return null
        when (ext) {
            "png" -> ByteArrayOutputStream().also { buffer ->
                check(javax.imageio.ImageIO.write(image, "png", buffer))
            }.toByteArray()
            in JPEG_EXTENSIONS -> {
                val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
                    ?: return null
                val param = writer.defaultWriteParam.apply {
                    if (canWriteCompressed()) {
                        compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                        val base = quality.coerceIn(0.92f, 0.99f)
                        val jitter = random.nextInt(3) * 0.005f
                        compressionQuality = (base - jitter).coerceIn(0.92f, 0.99f)
                    }
                }
                ByteArrayOutputStream().use { buffer ->
                    writer.output = javax.imageio.ImageIO.createImageOutputStream(buffer)
                    writer.write(null, javax.imageio.IIOImage(image, null, null), param)
                    writer.dispose()
                    buffer.toByteArray()
                }
            }
            else -> input
        }
    }.getOrNull()

    fun processBytes(
        entryName: String,
        bytes: ByteArray,
        random: Random,
        config: ProcessConfig,
    ): ByteArray? {
        val ext = resolveImageExt(entryName.substringAfterLast('/'))
        var working = bytes
        if (config.perceptualNoise) {
            working = applyPerceptualNoise(working, ext, random) ?: working
        }
        if (config.pngMicroCompress && ext == "png") {
            working = microCompressRaster(working, "png", config.microCompressQuality, random) ?: working
        }
        if (config.jpegMicroCompress && ext in JPEG_EXTENSIONS) {
            working = microCompressRaster(working, ext, config.microCompressQuality, random) ?: working
        }
        val token = config.metadataToken.ifBlank { "apk-fallback-${random.nextInt()}" }
        val fileName = entryName.substringAfterLast('/')
        if (hasShellMetadata(working, fileName)) return working
        val updated = when (ext) {
            "png" -> injectPngMetadata(working, random, token, config.pngExtraChunks)
            in JPEG_EXTENSIONS -> injectJpegMetadata(working, token, config.jpegMetadataMode)
            "webp" -> injectWebpMetadata(working, random, token, fileName)
            else -> null
        } ?: return null
        return updated.takeIf { ImageDecodeVerifier.verifyDecodable(it, fileName) }
    }

    /** WebP 结构校验（供 [ImageDecodeVerifier] 与单测使用）。 */
    internal fun verifyWebpStructure(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || !bytes.contentEquals("RIFF", 0) || !bytes.contentEquals("WEBP", 8)) {
            return false
        }
        val chunks = parseWebpChunks(bytes) ?: return false
        if (chunks.none { it.fourCC == "VP8 " || it.fourCC == "VP8L" }) return false
        val canvas = resolveCanvasSize(chunks) ?: return false
        if (canvas.first <= 0 || canvas.second <= 0) return false
        val vp8x = chunks.firstOrNull { it.fourCC == "VP8X" }?.data
        val hasXmp = chunks.any { it.fourCC == "XMP " }
        if (vp8x != null && vp8x.size >= 10) {
            val flags = vp8x[0].toInt() and 0xFF
            if (flags and VP8X_FLAG_XMP != 0 && !hasXmp) return false
            if (hasXmp) {
                if (flags and VP8X_FLAG_XMP == 0) return false
                val vp8xWidth = 1 + readInt24Le(vp8x, 4)
                val vp8xHeight = 1 + readInt24Le(vp8x, 7)
                if (vp8xWidth != canvas.first || vp8xHeight != canvas.second) return false
            }
        } else if (hasXmp) {
            return false
        }
        return true
    }

    /** 扩展 WebP（混合帧/动画/alpha 等）注入仍失败时记 skippedWebpExtended。 */
    internal fun isIntentionalWebpSkip(input: ByteArray): Boolean {
        if (input.size < 12 || !input.contentEquals("RIFF", 0) || !input.contentEquals("WEBP", 8)) {
            return false
        }
        val chunks = parseWebpChunks(input) ?: return false
        return isIntentionalWebpSkip(chunks)
    }

    private fun isIntentionalWebpSkip(chunks: List<WebpChunk>): Boolean {
        if (chunks.none { it.fourCC == "VP8 " || it.fourCC == "VP8L" }) return false
        if (chunks.any { it.fourCC == "VP8L" } && chunks.any { it.fourCC == "VP8 " }) return true
        if (chunks.any { it.fourCC == "ANIM" }) return true
        if (chunks.any { it.fourCC == "VP8X" } && chunks.any { it.fourCC == "ALPH" }) return true
        return false
    }

    /** 已注入 shell metadata 的图片跳过二次处理（compile overlay + APK fallback）。 */
    internal fun hasShellMetadata(bytes: ByteArray, fileName: String): Boolean {
        val ext = resolveImageExt(fileName)
        return when (ext) {
            "png" -> hasPngShellMetadata(bytes)
            in JPEG_EXTENSIONS -> hasJpegShellMetadata(bytes)
            "webp" -> hasWebpShellMetadata(bytes)
            else -> false
        }
    }

    private fun hasPngShellMetadata(bytes: ByteArray): Boolean {
        if (!hasPngSignature(bytes)) return false
        if (hasPngChunk(bytes, "sObf")) return true
        return bytes.indexOfSubArray(SHELL_PNG_TEXT_MARKER) >= 0
    }

    private fun hasJpegShellMetadata(bytes: ByteArray): Boolean =
        bytes.indexOfSubArray(SHELL_JPEG_MARKER) >= 0

    private fun hasWebpShellMetadata(bytes: ByteArray): Boolean {
        if (bytes.indexOfSubArray(SHELL_WEBP_XMP_MARKER) >= 0) return true
        if (bytes.indexOfSubArray(SHELL_WEBP_TOKEN_MARKER) >= 0) return true
        val chunks = parseWebpChunks(bytes) ?: return false
        return chunks.any { it.fourCC == WEBP_SHELL_CHUNK_FOURCC }
    }

    private fun ByteArray.indexOfSubArray(sub: ByteArray): Int {
        if (sub.isEmpty()) return 0
        for (start in indices) {
            if (start + sub.size > size) break
            var matched = true
            for (index in sub.indices) {
                if (this[start + index] != sub[index]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return -1
    }

    internal fun hasPngChunk(input: ByteArray, type: String): Boolean =
        findPngChunkOffset(input, type) != null

    internal fun hasPngSignature(input: ByteArray): Boolean =
        input.size >= PNG_SIGNATURE_BYTES.size && input.startsWith(PNG_SIGNATURE_BYTES)

    private fun injectPngMetadata(
        input: ByteArray,
        random: Random,
        token: String,
        extraChunks: Boolean,
    ): ByteArray? {
        if (input.size < 12 || !input.startsWith(PNG_SIGNATURE_BYTES)) return null
        val iendOffset = findPngChunkOffset(input, "IEND") ?: return null
        val chunks = mutableListOf<ByteArray>()
        chunks += buildPngChunk("tEXt", "shell\u0000$token".encodeToByteArray())
        if (extraChunks) {
            if (random.nextBoolean()) {
                chunks += buildPngChunk(
                    "iTXt",
                    buildITxtPayload("shell-i", token),
                )
            }
            chunks += buildPngChunk(
                "sObf",
                token.encodeToByteArray().copyOf(minOf(32, token.length.coerceAtLeast(1))),
            )
        }
        var output = input.copyOf(iendOffset)
        chunks.forEach { chunk -> output += chunk }
        output += input.copyOfRange(iendOffset, input.size)
        return output
    }

    private fun buildITxtPayload(keyword: String, text: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(keyword.encodeToByteArray())
        payload.write(0)
        payload.write(0) // compression flag
        payload.write(0) // compression method
        payload.write(0) // language
        payload.write(0) // translated keyword
        payload.write(text.encodeToByteArray())
        return payload.toByteArray()
    }

    private fun injectJpegMetadata(
        input: ByteArray,
        token: String,
        mode: JpegMetadataMode,
    ): ByteArray? {
        if (input.size < 4 || input[0] != 0xFF.toByte() || input[1] != 0xD8.toByte()) return null
        var bytes = input
        if (mode == JpegMetadataMode.EXIF || mode == JpegMetadataMode.BOTH) {
            bytes = insertJpegSegment(bytes, 0xE1.toByte(), buildExifApp1Payload(token)) ?: return null
        }
        if (mode == JpegMetadataMode.COM || mode == JpegMetadataMode.BOTH) {
            bytes = insertJpegComBeforeEoi(bytes, "shell-obfuscate-$token") ?: return null
        }
        return bytes
    }

    private fun buildExifApp1Payload(token: String): ByteArray {
        val header = "Exif\u0000\u0000".encodeToByteArray()
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )
        val note = token.encodeToByteArray().copyOf(minOf(48, token.length.coerceAtLeast(1)))
        return header + tiff + note
    }

    private fun insertJpegSegment(input: ByteArray, marker: Byte, payload: ByteArray): ByteArray? {
        val segmentLength = 2 + payload.size
        if (segmentLength > 0xFFFF) return null
        val segment = ByteArray(4 + payload.size)
        segment[0] = 0xFF.toByte()
        segment[1] = marker
        segment[2] = (segmentLength shr 8).toByte()
        segment[3] = (segmentLength and 0xFF).toByte()
        System.arraycopy(payload, 0, segment, 4, payload.size)
        return input.copyOf(2) + segment + input.copyOfRange(2, input.size)
    }

    private fun insertJpegComBeforeEoi(input: ByteArray, comment: String): ByteArray? {
        val eoiOffset = findJpegEoiOffset(input) ?: return null
        val commentBytes = comment.encodeToByteArray()
        val segmentLength = 2 + commentBytes.size
        if (segmentLength > 0xFFFF) return null
        val com = ByteArray(4 + commentBytes.size)
        com[0] = 0xFF.toByte()
        com[1] = 0xFE.toByte()
        com[2] = (segmentLength shr 8).toByte()
        com[3] = (segmentLength and 0xFF).toByte()
        System.arraycopy(commentBytes, 0, com, 4, commentBytes.size)
        return input.copyOf(eoiOffset) + com + input.copyOfRange(eoiOffset, input.size)
    }

    private fun injectWebpMetadata(
        input: ByteArray,
        random: Random,
        token: String,
        verifyName: String,
    ): ByteArray? {
        if (input.size < 12 || !input.contentEquals("RIFF", 0) || !input.contentEquals("WEBP", 8)) {
            return null
        }
        val chunks = parseWebpChunks(input) ?: return null
        if (chunks.none { it.fourCC == "VP8 " || it.fourCC == "VP8L" }) return null
        if (chunks.any { it.fourCC == "XMP " } || chunks.any { it.fourCC == WEBP_SHELL_CHUNK_FOURCC }) {
            return null
        }
        val hasMixedVp8 = chunks.any { it.fourCC == "VP8L" } && chunks.any { it.fourCC == "VP8 " }
        val hasVp8x = chunks.any { it.fourCC == "VP8X" }
        val hasVp8lOnly = chunks.any { it.fourCC == "VP8L" } && chunks.none { it.fourCC == "VP8 " }
        val strategies = buildList<(MutableList<WebpChunk>) -> ByteArray?> {
            if (hasMixedVp8) add { injectWebpMixedPrivateChunk(it, token) }
            if (hasVp8x) add { injectWebpXmpIntoExistingVp8x(it, random, token) }
            if (hasVp8lOnly) add { injectWebpPrivateChunk(it, token) }
            if (chunks.any { it.fourCC == "VP8 " } && !hasMixedVp8) {
                add { injectWebpWrapSimpleVp8WithXmp(it, random, token) }
            }
            add { injectWebpAppendPrivateChunkOnly(it, token) }
        }
        strategies.forEach { strategy ->
            val copy = cloneWebpChunks(chunks)
            val candidate = strategy(copy) ?: return@forEach
            if (ImageDecodeVerifier.verifyDecodable(candidate, verifyName)) return candidate
        }
        return null
    }

    private fun cloneWebpChunks(chunks: List<WebpChunk>): MutableList<WebpChunk> =
        chunks.map { chunk -> WebpChunk(chunk.fourCC, chunk.data.copyOf()) }.toMutableList()

    /** Tier D：任意 WebP 末尾追加 sObf，不改 VP8X/ANIM 结构。 */
    private fun injectWebpAppendPrivateChunkOnly(chunks: MutableList<WebpChunk>, token: String): ByteArray? {
        if (chunks.any { it.fourCC == WEBP_SHELL_CHUNK_FOURCC || it.fourCC == "XMP " }) return null
        if (chunks.none { it.fourCC == "VP8 " || it.fourCC == "VP8L" }) return null
        chunks.add(WebpChunk(WEBP_SHELL_CHUNK_FOURCC, buildWebpShellChunkPayload(token)))
        return buildWebpFile(chunks)
    }

    /** Tier C：VP8+VP8L 混合仅追加 sObf 私有 chunk，不引入 VP8X/XMP。 */
    private fun injectWebpMixedPrivateChunk(chunks: MutableList<WebpChunk>, token: String): ByteArray? {
        if (chunks.any { it.fourCC == WEBP_SHELL_CHUNK_FOURCC || it.fourCC == "XMP " }) return null
        chunks.add(WebpChunk(WEBP_SHELL_CHUNK_FOURCC, buildWebpShellChunkPayload(token)))
        return buildWebpFile(chunks)
    }

    /** Tier A：已有 VP8X 仅 OR XMP flag 并插入 XMP chunk，保留 canvas/reserved。 */
    private fun injectWebpXmpIntoExistingVp8x(
        chunks: MutableList<WebpChunk>,
        random: Random,
        token: String,
    ): ByteArray? {
        val vp8xIndex = chunks.indexOfFirst { it.fourCC == "VP8X" }
        if (vp8xIndex < 0) return null
        val vp8xData = chunks[vp8xIndex].data
        if (vp8xData.size < 10) return null
        val updatedVp8x = vp8xData.copyOf().also {
            it[0] = (it[0].toInt() or VP8X_FLAG_XMP).toByte()
        }
        chunks[vp8xIndex] = WebpChunk("VP8X", updatedVp8x)
        chunks.add(vp8xIndex + 1, WebpChunk("XMP ", buildMinimalXmpPacket(token, random)))
        return buildWebpFile(chunks)
    }

    /** Tier B：纯 VP8L 简单格式末尾追加 sObf 私有 chunk，不引入 VP8X。 */
    private fun injectWebpPrivateChunk(chunks: MutableList<WebpChunk>, token: String): ByteArray? {
        if (chunks.any { it.fourCC == "VP8X" || it.fourCC == "VP8 " }) return null
        if (chunks.none { it.fourCC == "VP8L" }) return null
        val payload = buildWebpShellChunkPayload(token)
        chunks.add(WebpChunk(WEBP_SHELL_CHUNK_FOURCC, payload))
        return buildWebpFile(chunks)
    }

    /** 简单有损 VP8：新建 VP8X + XMP。 */
    private fun injectWebpWrapSimpleVp8WithXmp(
        chunks: MutableList<WebpChunk>,
        random: Random,
        token: String,
    ): ByteArray? {
        val canvas = resolveCanvasSize(chunks) ?: return null
        val xmpData = buildMinimalXmpPacket(token, random)
        chunks.add(0, WebpChunk("VP8X", buildVp8xPayload(canvas.first, canvas.second, chunkFeatureFlags(chunks))))
        chunks.add(1, WebpChunk("XMP ", xmpData))
        val flags = chunkFeatureFlags(chunks) or VP8X_FLAG_XMP
        chunks[0] = WebpChunk("VP8X", buildVp8xPayload(canvas.first, canvas.second, flags))
        return buildWebpFile(chunks)
    }

    private fun buildWebpShellChunkPayload(token: String): ByteArray {
        val text = "shell:token=$token"
        return text.encodeToByteArray().copyOf(minOf(64, text.length.coerceAtLeast(1)))
    }

    /** VP8X flags：与 WebP 扩展格式一致（XMP=bit3）。 */
    private fun chunkFeatureFlags(chunks: List<WebpChunk>): Int {
        var flags = 0
        if (chunks.any { it.fourCC == "ICCP" }) flags = flags or VP8X_FLAG_ICC
        if (chunks.any { it.fourCC == "EXIF" }) flags = flags or VP8X_FLAG_EXIF
        if (chunks.any { it.fourCC == "XMP " }) flags = flags or VP8X_FLAG_XMP
        if (chunks.any { it.fourCC == "ANIM" }) flags = flags or VP8X_FLAG_ANIMATION
        if (chunks.any { it.fourCC == "ALPH" }) flags = flags or VP8X_FLAG_ALPHA
        val vp8l = chunks.firstOrNull { it.fourCC == "VP8L" }?.data
        if (vp8l != null && vp8l.size >= 5) {
            val bits = (vp8l[1].toLong() and 0xFF) or
                ((vp8l[2].toLong() and 0xFF) shl 8) or
                ((vp8l[3].toLong() and 0xFF) shl 16) or
                ((vp8l[4].toLong() and 0xFF) shl 24)
            if ((bits shr 28).and(1L) != 0L) flags = flags or VP8X_FLAG_ALPHA
        }
        return flags
    }

    private fun resolveCanvasSize(chunks: List<WebpChunk>): Pair<Int, Int>? {
        chunks.firstOrNull { it.fourCC == "VP8X" }?.data?.let { data ->
            if (data.size >= 10) {
                val width = 1 + readInt24Le(data, 4)
                val height = 1 + readInt24Le(data, 7)
                if (width > 1 && height > 1) return width to height
            }
        }
        chunks.firstOrNull { it.fourCC == "VP8 " }?.data?.let { return parseVp8Dimensions(it) }
        chunks.firstOrNull { it.fourCC == "VP8L" }?.data?.let { return parseVp8lDimensions(it) }
        return null
    }

    /** libwebp VP8GetInfo：从 VP8 chunk payload 读取 canvas 宽高。 */
    private fun parseVp8Dimensions(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 10) return null
        val bits = (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16)
        if (bits and 1 != 0) return null
        if (data[3] != 0x9d.toByte() || data[4] != 0x01.toByte() || data[5] != 0x2a.toByte()) return null
        val width = ((data[7].toInt() and 0xFF) shl 8 or (data[6].toInt() and 0xFF)) and 0x3fff
        val height = ((data[9].toInt() and 0xFF) shl 8 or (data[8].toInt() and 0xFF)) and 0x3fff
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private fun parseVp8lDimensions(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 5 || data[0] != 0x2f.toByte()) return null
        val bits = (data[1].toLong() and 0xFF) or
            ((data[2].toLong() and 0xFF) shl 8) or
            ((data[3].toLong() and 0xFF) shl 16) or
            ((data[4].toLong() and 0xFF) shl 24)
        val width = 1 + ((bits and 0x3FFF).toInt())
        val height = 1 + (((bits shr 14) and 0x3FFF).toInt())
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private fun buildVp8xPayload(width: Int, height: Int, flags: Int): ByteArray {
        require(width in 1..0x3FFF && height in 1..0x3FFF)
        val payload = ByteArray(10)
        payload[0] = flags.toByte()
        val widthMinusOne = width - 1
        val heightMinusOne = height - 1
        payload[4] = (widthMinusOne and 0xFF).toByte()
        payload[5] = ((widthMinusOne shr 8) and 0xFF).toByte()
        payload[6] = ((widthMinusOne shr 16) and 0xFF).toByte()
        payload[7] = (heightMinusOne and 0xFF).toByte()
        payload[8] = ((heightMinusOne shr 8) and 0xFF).toByte()
        payload[9] = ((heightMinusOne shr 16) and 0xFF).toByte()
        return payload
    }

    private fun readInt24Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)

    private fun findPngChunkOffset(input: ByteArray, type: String): Int? {
        var offset = PNG_SIGNATURE_BYTES.size
        val typeBytes = type.encodeToByteArray()
        while (offset + 12 <= input.size) {
            val length = readIntBE(input, offset)
            if (offset + 12 + length > input.size) return null
            if (input.copyOfRange(offset + 4, offset + 8).contentEquals(typeBytes)) return offset
            offset += 12 + length
        }
        return null
    }

    private fun buildPngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.encodeToByteArray()
        require(typeBytes.size == 4)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        val chunk = ByteArray(12 + data.size)
        writeIntBE(chunk, 0, data.size)
        System.arraycopy(typeBytes, 0, chunk, 4, 4)
        System.arraycopy(data, 0, chunk, 8, data.size)
        writeIntBE(chunk, 8 + data.size, crc)
        return chunk
    }

    private fun findJpegEoiOffset(input: ByteArray): Int? {
        for (index in input.size - 2 downTo 0) {
            if (input[index] == 0xFF.toByte() && input[index + 1] == 0xD9.toByte()) {
                return index
            }
        }
        return null
    }

    private data class WebpChunk(val fourCC: String, val data: ByteArray)

    private fun parseWebpChunks(input: ByteArray): List<WebpChunk>? {
        val chunks = mutableListOf<WebpChunk>()
        var offset = 12
        while (offset + 8 <= input.size) {
            val fourCC = input.decodeFourCC(offset)
            val size = readIntLE(input, offset + 4)
            val dataStart = offset + 8
            val dataEnd = dataStart + size
            if (dataEnd > input.size) return null
            chunks.add(WebpChunk(fourCC, input.copyOfRange(dataStart, dataEnd)))
            val paddedSize = if (size and 1 == 1) size + 1 else size
            offset = dataStart + paddedSize
        }
        return chunks
    }

    private fun buildWebpFile(chunks: List<WebpChunk>): ByteArray {
        val body = ByteArrayOutputStream()
        chunks.forEach { chunk ->
            body.write(chunk.fourCC.encodeToByteArray())
            writeIntLE(body, chunk.data.size)
            body.write(chunk.data)
            if (chunk.data.size and 1 == 1) {
                body.write(0)
            }
        }
        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream(bodyBytes.size + 12)
        output.write("RIFF".encodeToByteArray())
        writeIntLE(output, bodyBytes.size + 4)
        output.write("WEBP".encodeToByteArray())
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMinimalXmpPacket(token: String, random: Random): ByteArray =
        """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc4d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description shell:token="$token" shell:nonce="${random.nextInt()}" xmlns:shell="https://shell-obfuscate.local/metadata"/>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent().encodeToByteArray()

    private fun ByteArray.contentEquals(text: String, offset: Int): Boolean {
        val bytes = text.encodeToByteArray()
        if (offset + bytes.size > size) return false
        return bytes.indices.all { index -> this[offset + index] == bytes[index] }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.decodeFourCC(offset: Int): String =
        String(this, offset, 4, Charsets.US_ASCII)

    private fun readIntBE(input: ByteArray, offset: Int): Int =
        ((input[offset].toInt() and 0xFF) shl 24) or
            ((input[offset + 1].toInt() and 0xFF) shl 16) or
            ((input[offset + 2].toInt() and 0xFF) shl 8) or
            (input[offset + 3].toInt() and 0xFF)

    private fun readIntLE(input: ByteArray, offset: Int): Int =
        (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8) or
            ((input[offset + 2].toInt() and 0xFF) shl 16) or
            ((input[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 24).toByte()
        target[offset + 1] = (value shr 16).toByte()
        target[offset + 2] = (value shr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeIntLE(target: ByteArrayOutputStream, value: Int) {
        target.write(value and 0xFF)
        target.write((value shr 8) and 0xFF)
        target.write((value shr 16) and 0xFF)
        target.write((value shr 24) and 0xFF)
    }

    private val PNG_SIGNATURE_BYTES = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val JPEG_EXTENSIONS = setOf("jpg", "jpeg")
    private val NOISE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    private const val VP8X_FLAG_ICC = 0x01
    private const val VP8X_FLAG_ALPHA = 0x02
    private const val VP8X_FLAG_EXIF = 0x04
    private const val VP8X_FLAG_XMP = 0x08
    private const val VP8X_FLAG_ANIMATION = 0x10

    private const val WEBP_SHELL_CHUNK_FOURCC = "sObf"

    private val SHELL_PNG_TEXT_MARKER = "shell\u0000".encodeToByteArray()
    private val SHELL_JPEG_MARKER = "shell-obfuscate-".encodeToByteArray()
    private val SHELL_WEBP_XMP_MARKER = "shell-obfuscate.local/metadata".encodeToByteArray()
    private val SHELL_WEBP_TOKEN_MARKER = "shell:token=".encodeToByteArray()
}
