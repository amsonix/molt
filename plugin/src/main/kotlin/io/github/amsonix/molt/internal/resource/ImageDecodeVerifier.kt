package io.github.amsonix.molt.internal.resource

/**
 * 注入后解码/结构校验：PNG/JPEG 走 ImageIO；WebP 走 chunk + canvas 结构（JVM 常无 webp ImageIO 插件）。
 */
internal object ImageDecodeVerifier {

    fun verifyDecodable(bytes: ByteArray, fileName: String): Boolean {
        val ext = ImageMetadataAntiDetectProcessor.resolveImageExt(fileName)
        return when (ext) {
            "png" -> verifyPngDecodable(bytes, fileName)
            "jpg", "jpeg" -> verifyJpegDecodable(bytes)
            "webp" -> ImageMetadataAntiDetectProcessor.verifyWebpStructure(bytes)
            else -> false
        }
    }

    private fun verifyPngDecodable(bytes: ByteArray, fileName: String): Boolean {
        if (bytes.size < 8 || !ImageMetadataAntiDetectProcessor.hasPngSignature(bytes)) {
            return false
        }
        if (!verifyPngChunkNames(bytes)) return false
        val image = runCatching {
            javax.imageio.ImageIO.read(bytes.inputStream())
        }.getOrNull() ?: return false
        if (image.width <= 0 || image.height <= 0) return false
        return true
    }

    /**
     * 模拟 libpng 1.6.47+（Android 16 模拟器/设备内置）的严格 chunk 名校验。
     * PNG 规范要求 chunk 名第 3 字符（reserved bit）必须是大写字母（A-Z）；
     * 旧版 libpng 只做宽松字母范围检查，ImageIO 也容忍，但 1.6.47 会直接报
     * "bad header (invalid type)" 导致解码失败。此处按 1.6.47 的 check_chunk_name
     * 规则校验，避免生成在 Android 16+ 上不可解码的 PNG。
     */
    private fun verifyPngChunkNames(bytes: ByteArray): Boolean {
        var offset = PNG_SIGNATURE_SIZE
        while (offset + 8 <= bytes.size) {
            if (offset + 12 > bytes.size) return false
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            val type = bytes.copyOfRange(offset + 4, offset + 8)
            if (!isValidPngChunkName(type)) return false
            if (offset + 12 + length > bytes.size) return false
            offset += 12 + length
        }
        return true
    }

    /**
     * libpng 1.6.47 check_chunk_name 规则：去掉第 3 字节的 bit5 后，
     * 其余每个字节都必须在 A-Z（65-90）范围，即第 1/2/4 字符可为大写或小写，
     * 但第 3 字符（reserved bit）必须是大写字母。
     */
    private fun isValidPngChunkName(type: ByteArray): Boolean {
        if (type.size != 4) return false
        for (i in type.indices) {
            val c = type[i].toInt() and 0xFF
            if (c < 65 || c > 122 || (c > 90 && c < 97)) return false
        }
        val reserved = type[2].toInt() and 0xFF
        return reserved in 65..90
    }

    private const val PNG_SIGNATURE_SIZE = 8

    private fun verifyJpegDecodable(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            return false
        }
        val image = runCatching {
            javax.imageio.ImageIO.read(bytes.inputStream())
        }.getOrNull() ?: return false
        return image.width > 0 && image.height > 0
    }
}
