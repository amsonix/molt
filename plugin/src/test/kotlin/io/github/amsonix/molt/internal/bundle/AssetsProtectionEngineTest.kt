package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AssetsProtectionEngineTest {

    private val config = AssetsProtectionConfig(
        seed = 42,
        filePatterns = listOf("*.json", "*.txt", "*.xml"),
        junkFileCount = 2,
        excludePatterns = listOf("secret.txt"),
        assetsPrefix = "assets/",
    )

    @Test
    fun patch_injectsJsonFieldAndJunkFiles_keepsBinaryAndExcludedUntouched() {
        val zip = buildZip(
            "assets/config.json" to """{"key": "value"}""".encodeToByteArray(),
            "assets/plain.txt" to "hello world\n".encodeToByteArray(),
            "assets/config.xml" to "<config>\n  <item>1</item>\n</config>\n".encodeToByteArray(),
            "assets/secret.txt" to "do not touch".encodeToByteArray(),
            "assets/icon.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "lib/arm64/x.so" to byteArrayOf(0x7F, 0x45, 0x4C, 0x46),
        )

        AssetsProtectionEngine.patchZipInPlace(zip, config)

        ZipFile(zip).use { zf ->
            val json = zf.getInputStream(zf.getEntry("assets/config.json")).use { it.readBytes() }.decodeToString()
            assertTrue("json must gain a junk field", json.contains("\"molt_"))
            assertTrue("original json key must remain", json.contains("\"key\": \"value\""))
            assertTrue("json must stay valid object", json.trim().startsWith("{") && json.trim().endsWith("}"))

            val txt = zf.getInputStream(zf.getEntry("assets/plain.txt")).use { it.readBytes() }.decodeToString()
            assertEquals("non-XML text must stay untouched", "hello world\n", txt)

            val xml = zf.getInputStream(zf.getEntry("assets/config.xml")).use { it.readBytes() }.decodeToString()
            assertTrue("xml-ish text must gain a comment", xml.endsWith("<!-- molt -->\n"))

            val secret = zf.getInputStream(zf.getEntry("assets/secret.txt")).use { it.readBytes() }.decodeToString()
            assertEquals("excluded file must stay untouched", "do not touch", secret)

            val png = zf.getInputStream(zf.getEntry("assets/icon.png")).use { it.readBytes() }
            assertTrue("binary must stay untouched", png.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))

            val junkEntries = zf.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("assets/molt_junk_") }
                .toList()
            assertEquals("junk files must be injected", 2, junkEntries.size)
        }
    }

    @Test
    fun patch_seedChangesJunkNames_identicalSeedStaysStable() {
        val zipA = buildZip("assets/a.json" to """{"k": 1}""".encodeToByteArray())
        val zipB = buildZip("assets/a.json" to """{"k": 1}""".encodeToByteArray())
        val zipC = buildZip("assets/a.json" to """{"k": 1}""".encodeToByteArray())

        AssetsProtectionEngine.patchZipInPlace(zipA, config)
        AssetsProtectionEngine.patchZipInPlace(zipB, config)
        AssetsProtectionEngine.patchZipInPlace(
            zipC,
            config.copy(seed = 43),
        )

        fun junkNames(zip: File): List<String> =
            ZipFile(zip).use { zf ->
                zf.entries().asSequence().map { it.name }.filter { it.contains("molt_junk_") }.sorted().toList()
            }

        assertEquals("same seed must reproduce same junk layout", junkNames(zipA), junkNames(zipB))
        assertFalse("different seed must differ", junkNames(zipA) == junkNames(zipC))
    }

    private fun buildZip(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("assets-protect", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.sortedBy { it.first }.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }
}
