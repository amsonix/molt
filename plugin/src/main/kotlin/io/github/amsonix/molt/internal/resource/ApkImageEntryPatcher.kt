package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.util.SeedRandom

/** APK proto 重打包时对 res 内图片 entry 做 metadata 兜底（compile overlay 漏网）。 */
object ApkImageEntryPatcher {

    @JvmStatic
    fun patchIfNeeded(
        entryName: String,
        bytes: ByteArray,
        seed: Int,
        metadataScope: String,
        enabled: Boolean,
        perceptualNoise: Boolean = false,
    ): ByteArray {
        if (!enabled || !isResImageEntry(entryName)) return bytes
        val fileName = entryName.substringAfterLast('/')
        if (ImageMetadataAntiDetectProcessor.hasShellMetadata(bytes, fileName)) return bytes
        val ext = ImageMetadataAntiDetectProcessor.resolveImageExt(fileName)
        if (ext !in SUPPORTED) return bytes
        val random = SeedRandom.create(seed, "apk-image-fallback-$entryName")
        val token = "$metadataScope/$entryName"
        val config = ImageMetadataAntiDetectProcessor.ProcessConfig(
            pngMicroCompress = false,
            jpegMicroCompress = false,
            jpegMetadataMode = ImageMetadataAntiDetectProcessor.JpegMetadataMode.BOTH,
            pngExtraChunks = true,
            perceptualNoise = perceptualNoise,
            metadataToken = token,
        )
        val patched = ImageMetadataAntiDetectProcessor.processBytes(
            entryName = entryName,
            bytes = bytes,
            random = random,
            config = config,
        ) ?: return bytes
        if (patched.contentEquals(bytes)) return bytes
        return if (ImageDecodeVerifier.verifyDecodable(patched, fileName)) patched else bytes
    }

    @JvmStatic
    fun isImageEntry(entryName: String): Boolean {
        val fileName = entryName.substringAfterLast('/').lowercase()
        return fileName.endsWith(".png") ||
            fileName.endsWith(".jpg") ||
            fileName.endsWith(".jpeg") ||
            fileName.endsWith(".webp") ||
            fileName.endsWith(".9.png")
    }

    private val SUPPORTED = setOf("png", "jpg", "jpeg", "webp")

    private fun isResImageEntry(entryName: String): Boolean =
        entryName.startsWith("res/") || entryName.contains("/res/")
}
