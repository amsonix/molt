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
        val image = runCatching {
            javax.imageio.ImageIO.read(bytes.inputStream())
        }.getOrNull() ?: return false
        if (image.width <= 0 || image.height <= 0) return false
        return true
    }

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
