package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArtProfilePatcherTest {

    @Test
    fun patchInPlace_replacesApkBaselineProfileEntries() {
        val zip = File.createTempFile("shell-profile-apk", ".apk")
        val originalProf = byteArrayOf(1, 2, 3)
        val originalProfm = byteArrayOf(9)
        val newProf = byteArrayOf(4, 5, 6, 7)
        val newProfm = byteArrayOf(8, 8)
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("classes.dex"))
                out.write(byteArrayOf(0x64, 0x65, 0x78, 0x0a))
                out.closeEntry()
                out.putNextEntry(ZipEntry(ZipArtProfilePatcher.APK_BASELINE_PROF))
                out.write(originalProf)
                out.closeEntry()
                out.putNextEntry(ZipEntry(ZipArtProfilePatcher.APK_BASELINE_PROFM))
                out.write(originalProfm)
                out.closeEntry()
            }
            ZipArtProfilePatcher.patchInPlace(zip, newProf, newProfm)
            val prof = readZipEntry(zip, ZipArtProfilePatcher.APK_BASELINE_PROF)
            val profm = readZipEntry(zip, ZipArtProfilePatcher.APK_BASELINE_PROFM)
            assertTrue(prof.contentEquals(newProf))
            assertTrue(profm.contentEquals(newProfm))
        } finally {
            zip.delete()
        }
    }

    private fun readZipEntry(zipFile: File, entryName: String): ByteArray =
        java.util.zip.ZipFile(zipFile).use { zip ->
            zip.getInputStream(zip.getEntry(entryName)).use { it.readBytes() }
        }
}
