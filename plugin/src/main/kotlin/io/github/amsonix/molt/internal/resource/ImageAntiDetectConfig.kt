package io.github.amsonix.molt.internal.resource

internal fun parseJpegMetadataMode(raw: String): ImageMetadataAntiDetectProcessor.JpegMetadataMode =
    when (raw.lowercase()) {
        "com" -> ImageMetadataAntiDetectProcessor.JpegMetadataMode.COM
        "exif" -> ImageMetadataAntiDetectProcessor.JpegMetadataMode.EXIF
        else -> ImageMetadataAntiDetectProcessor.JpegMetadataMode.BOTH
    }
