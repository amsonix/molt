package io.github.amsonix.molt.internal.bundle

import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** ZIP entry 的流式复制与元数据保留。 */
internal object ZipEntryWriter {

    @JvmStatic
    @Throws(IOException::class)
    fun copy(
        zipOut: ZipOutputStream,
        zipIn: ZipFile,
        source: ZipEntry,
        outputName: String,
    ) {
        val isStored = shouldStore(source, outputName)
        val canReuseStoredMetadata =
            isStored && source.method == ZipEntry.STORED && source.name == outputName
        val stats = if (isStored && !canReuseStoredMetadata) {
            calculateStats(zipIn, source)
        } else {
            null
        }
        val output = createEntry(
            source = source,
            outputName = outputName,
            isStored = isStored,
            size = stats?.size ?: source.size,
            crc = stats?.crc ?: source.crc,
        )
        zipOut.putNextEntry(output)
        zipIn.getInputStream(source).use { input -> input.copyTo(zipOut) }
        zipOut.closeEntry()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeBytes(
        zipOut: ZipOutputStream,
        source: ZipEntry,
        outputName: String,
        bytes: ByteArray,
        contentsChanged: Boolean,
    ) {
        val isStored = shouldStore(source, outputName)
        val canReuseStoredMetadata =
            isStored &&
                !contentsChanged &&
                source.method == ZipEntry.STORED &&
                source.name == outputName &&
                source.size == bytes.size.toLong()
        val crc = if (isStored && !canReuseStoredMetadata) {
            CRC32().apply { update(bytes) }.value
        } else {
            source.crc
        }
        val output = createEntry(
            source = source,
            outputName = outputName,
            isStored = isStored,
            size = bytes.size.toLong(),
            crc = crc,
        )
        zipOut.putNextEntry(output)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    private fun createEntry(
        source: ZipEntry,
        outputName: String,
        isStored: Boolean,
        size: Long,
        crc: Long,
    ): ZipEntry = ZipEntry(outputName).apply {
        if (source.time >= 0L) time = source.time
        source.extra?.let { extra = it }
        source.comment?.let { comment = it }
        if (isStored) {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc
        } else {
            method = ZipEntry.DEFLATED
        }
    }

    private fun shouldStore(source: ZipEntry, outputName: String): Boolean =
        source.method == ZipEntry.STORED ||
            (outputName.startsWith("lib/") && outputName.endsWith(".so"))

    private fun calculateStats(zipIn: ZipFile, source: ZipEntry): EntryStats {
        val crc = CRC32()
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        zipIn.getInputStream(source).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
                size += read
            }
        }
        return EntryStats(size = size, crc = crc.value)
    }

    private data class EntryStats(
        val size: Long,
        val crc: Long,
    )
}
