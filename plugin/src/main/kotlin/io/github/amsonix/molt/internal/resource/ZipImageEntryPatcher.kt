package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.bundle.ZipEntryWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** APK/AAB zip 内 res 图片 metadata 兜底（compile overlay 漏网）。 */
internal object ZipImageEntryPatcher {

    fun patchZipInPlace(
        zipFile: File,
        seed: Int,
        metadataScope: String,
        enabled: Boolean,
        perceptualNoise: Boolean = false,
    ): List<ImagePatchRecord> {
        if (!enabled || !zipFile.isFile) return emptyList()
        val temp = File.createTempFile("shell-zip-image-patch", ".zip", zipFile.parentFile)
        val records = mutableListOf<ImagePatchRecord>()
        try {
            ZipFile(zipFile).use { zipIn ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zipOut ->
                    zipIn.entries().asSequence().forEach { entry ->
                        val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                        if (!entry.isDirectory && isPatchableImageEntry(entry.name)) {
                            val patched = ApkImageEntryPatcher.patchIfNeeded(
                                entryName = entry.name,
                                bytes = bytes,
                                seed = seed,
                                metadataScope = metadataScope,
                                enabled = true,
                                perceptualNoise = perceptualNoise,
                            )
                            if (!patched.contentEquals(bytes)) {
                                records += ImagePatchRecord(
                                    entryName = entry.name,
                                    sourceMd5 = io.github.amsonix.molt.internal.resource.ImageMetadataAntiDetectProcessor.md5Hex(bytes),
                                    outputMd5 = io.github.amsonix.molt.internal.resource.ImageMetadataAntiDetectProcessor.md5Hex(patched),
                                )
                            }
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = patched,
                                contentsChanged = !patched.contentEquals(bytes),
                            )
                        } else {
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = bytes,
                                contentsChanged = false,
                            )
                        }
                    }
                }
            }
            if (records.isNotEmpty()) {
                temp.copyTo(zipFile, overwrite = true)
            }
        } finally {
            temp.delete()
        }
        return records
    }

    fun isPatchableImageEntry(name: String): Boolean {
        if (!name.contains("/res/") && !name.startsWith("res/")) return false
        return ApkImageEntryPatcher.isImageEntry(name)
    }
}

/** transform 阶段图片 metadata 兜底注入记录（供 verify 校验注入未丢失）。 */
internal data class ImagePatchRecord(
    val entryName: String,
    val sourceMd5: String,
    val outputMd5: String,
)
