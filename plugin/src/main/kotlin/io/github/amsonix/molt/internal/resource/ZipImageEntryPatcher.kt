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
    ): Int {
        if (!enabled || !zipFile.isFile) return 0
        val temp = File.createTempFile("shell-zip-image-patch", ".zip", zipFile.parentFile)
        var patchedCount = 0
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
                                patchedCount++
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
            if (patchedCount > 0) {
                temp.copyTo(zipFile, overwrite = true)
            }
        } finally {
            temp.delete()
        }
        return patchedCount
    }

    fun isPatchableImageEntry(name: String): Boolean {
        if (!name.contains("/res/") && !name.startsWith("res/")) return false
        return ApkImageEntryPatcher.isImageEntry(name)
    }
}
