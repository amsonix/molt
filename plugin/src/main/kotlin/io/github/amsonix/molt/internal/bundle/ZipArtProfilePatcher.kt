package io.github.amsonix.molt.internal.bundle

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 替换 APK/AAB 内 baseline.prof / baseline.profm。 */
internal object ZipArtProfilePatcher {

    const val APK_BASELINE_PROF = "assets/dexopt/baseline.prof"
    const val APK_BASELINE_PROFM = "assets/dexopt/baseline.profm"
    const val AAB_BASELINE_PROF = "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof"
    const val AAB_BASELINE_PROFM = "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm"

    @JvmStatic
    fun patchInPlace(zipFile: File, baselineProf: ByteArray, baselineProfm: ByteArray) {
        val temp = File.createTempFile("shell-art-profile", ".zip", zipFile.parentFile)
        try {
            patch(zipFile, temp, baselineProf, baselineProfm)
            temp.copyTo(zipFile, overwrite = true)
        } finally {
            temp.delete()
        }
    }

    private fun patch(
        input: File,
        output: File,
        baselineProf: ByteArray,
        baselineProfm: ByteArray,
    ) {
        ZipFile(input).use { zipIn ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zipOut ->
                var patchedProf = false
                var patchedProfm = false
                zipIn.entries().asSequence().forEach { entry ->
                    when (entry.name) {
                        APK_BASELINE_PROF, AAB_BASELINE_PROF -> {
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = baselineProf,
                                contentsChanged = true,
                            )
                            patchedProf = true
                        }
                        APK_BASELINE_PROFM, AAB_BASELINE_PROFM -> {
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = baselineProfm,
                                contentsChanged = true,
                            )
                            patchedProfm = true
                        }
                        else -> ZipEntryWriter.copy(zipOut, zipIn, entry, entry.name)
                    }
                }
                check(patchedProf) { "baseline.prof entry missing in ${input.name}" }
                check(patchedProfm) { "baseline.profm entry missing in ${input.name}" }
            }
        }
    }
}
