package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipEntryWriterTest {

    @Test
    fun copy_preservesMetadataAndKeepsStoredEntries() {
        withTempDirectory { directory ->
            val input = File(directory, "input.zip")
            val output = File(directory, "output.zip")
            val storedBytes = "stored-payload".toByteArray()
            val nativeBytes = "native-payload".toByteArray()
            createInputZip(input, storedBytes, nativeBytes)

            ZipFile(input).use { zipIn ->
                ZipOutputStream(FileOutputStream(output)).use { zipOut ->
                    zipIn.entries().asSequence().forEach { entry ->
                        ZipEntryWriter.copy(zipOut, zipIn, entry, entry.name)
                    }
                }
            }

            ZipFile(input).use { sourceZip ->
                ZipFile(output).use { outputZip ->
                    assertMetadataEquals(
                        sourceZip.getEntry(STORED_ENTRY),
                        outputZip.getEntry(STORED_ENTRY),
                    )
                    assertEquals(ZipEntry.STORED, outputZip.getEntry(STORED_ENTRY).method)
                    assertEquals(ZipEntry.STORED, outputZip.getEntry(NATIVE_ENTRY).method)
                    assertArrayEquals(
                        storedBytes,
                        outputZip.getInputStream(outputZip.getEntry(STORED_ENTRY)).readBytes(),
                    )
                    assertArrayEquals(
                        nativeBytes,
                        outputZip.getInputStream(outputZip.getEntry(NATIVE_ENTRY)).readBytes(),
                    )
                }
            }
        }
    }

    @Test
    fun writeBytes_changedStoredEntryRecalculatesSizeAndCrc() {
        withTempDirectory { directory ->
            val input = File(directory, "input.zip")
            val output = File(directory, "output.zip")
            createInputZip(input, "old".toByteArray(), "native".toByteArray())
            val changedBytes = "changed-stored-payload".toByteArray()

            ZipFile(input).use { zipIn ->
                val source = zipIn.getEntry(STORED_ENTRY)
                ZipOutputStream(FileOutputStream(output)).use { zipOut ->
                    ZipEntryWriter.writeBytes(
                        zipOut = zipOut,
                        source = source,
                        outputName = source.name,
                        bytes = changedBytes,
                        contentsChanged = true,
                    )
                }
            }

            ZipFile(output).use { zip ->
                val entry = zip.getEntry(STORED_ENTRY)
                val expectedCrc = CRC32().apply { update(changedBytes) }.value
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(changedBytes.size.toLong(), entry.size)
                assertEquals(expectedCrc, entry.crc)
                assertEquals(ENTRY_COMMENT, entry.comment)
                assertArrayEquals(changedBytes, zip.getInputStream(entry).readBytes())
            }
        }
    }

    private fun createInputZip(input: File, storedBytes: ByteArray, nativeBytes: ByteArray) {
        ZipOutputStream(FileOutputStream(input)).use { zip ->
            val storedCrc = CRC32().apply { update(storedBytes) }
            zip.putNextEntry(
                ZipEntry(STORED_ENTRY).apply {
                    time = ENTRY_TIME
                    extra = ENTRY_EXTRA
                    comment = ENTRY_COMMENT
                    method = ZipEntry.STORED
                    size = storedBytes.size.toLong()
                    compressedSize = storedBytes.size.toLong()
                    crc = storedCrc.value
                },
            )
            zip.write(storedBytes)
            zip.closeEntry()

            zip.putNextEntry(
                ZipEntry(NATIVE_ENTRY).apply {
                    time = ENTRY_TIME
                    extra = ENTRY_EXTRA
                    comment = ENTRY_COMMENT
                    method = ZipEntry.DEFLATED
                },
            )
            zip.write(nativeBytes)
            zip.closeEntry()
        }
    }

    private fun assertMetadataEquals(source: ZipEntry, output: ZipEntry) {
        assertEquals(source.time, output.time)
        assertArrayEquals(source.extra, output.extra)
        assertEquals(source.comment, output.comment)
        assertEquals(source.size, output.size)
        assertEquals(source.crc, output.crc)
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("zip-entry-writer-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val STORED_ENTRY = "assets/payload.bin"
        const val NATIVE_ENTRY = "lib/arm64-v8a/libsample.so"
        const val ENTRY_COMMENT = "entry-comment"
        const val ENTRY_TIME = 1_600_000_000_000L
        val ENTRY_EXTRA = byteArrayOf(0x34, 0x12, 0x02, 0x00, 0x01, 0x02)
    }
}
