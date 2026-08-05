package io.github.amsonix.molt.internal.bundle

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 生成 identity round-trip APK 供设备验证 DexPool 本身是否致 crash。 */
class DexIdentityRoundTripApkGeneratorTest {

    @Test
    fun generateIdentityRoundTripApk() {
        val root = IntegrationTestAssumptions.projectRoot()
        val unsigned = IntegrationTestAssumptions.assumeIntegrationApk(root)
        val outApk = File(
            root,
            "app/build/outputs/apk/google/release/identity-roundtrip-${unsigned.nameWithoutExtension}.apk",
        )
        ZipFile(unsigned).use { zipIn ->
            ZipOutputStream(FileOutputStream(outApk)).use { zipOut ->
                zipIn.entries().asIterator().forEach { entry ->
                    var bytes = zipIn.getInputStream(entry).readBytes()
                    if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                        bytes = identityRoundTrip(bytes)
                        println("${entry.name}: ${bytes.size}")
                    }
                    writeZipEntry(zipOut, entry, bytes)
                }
            }
        }
        println("written ${outApk.path}")
    }

    private fun writeZipEntry(zipOut: ZipOutputStream, source: ZipEntry, bytes: ByteArray) {
        val outEntry = ZipEntry(source.name).apply {
            time = source.time
            extra = source.extra
            comment = source.comment
        }
        if (source.method == ZipEntry.STORED ||
            (source.name.startsWith("lib/") && source.name.endsWith(".so"))
        ) {
            val crc = java.util.zip.CRC32().apply { update(bytes) }
            outEntry.method = ZipEntry.STORED
            outEntry.size = bytes.size.toLong()
            outEntry.compressedSize = bytes.size.toLong()
            outEntry.crc = crc.value
        } else {
            outEntry.method = ZipEntry.DEFLATED
        }
        zipOut.putNextEntry(outEntry)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    private fun identityRoundTrip(dexBytes: ByteArray): ByteArray {
        val dexFile = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(dexBytes),
        )
        val pool = DexPool(dexFile.opcodes)
        for (classDef in dexFile.classes) {
            pool.internClass(classDef)
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }
}
