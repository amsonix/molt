package io.github.amsonix.molt.internal.bundle

import com.android.aapt.Resources
import io.github.amsonix.molt.internal.rename.RenameMapping
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipPostR8RenameProcessorTest {

    @Test
    fun processZip_twoPassRewritePatchesAllDexAndStreamsOtherEntries() {
        withTempDirectory { directory ->
            val input = File(directory, "input.apk")
            val output = File(directory, "output.apk")
            val firstOriginal = "com.example.First"
            val secondOriginal = "com.example.Second"
            val firstMapped = "a.b.First"
            val secondMapped = "c.d.Second"
            val firstDex = buildDex(descriptor(firstOriginal))
            val secondDex = buildDex(descriptor(secondOriginal))
            val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
            createInputZip(input, firstDex, secondDex, payload)

            val result = ZipPostR8RenameProcessor.processZip(
                input = input,
                output = output,
                config = ZipPostR8RenameProcessor.Config(
                    componentMapping = RenameMapping.fromForward(
                        mapOf(
                            firstOriginal to firstMapped,
                            secondOriginal to secondMapped,
                        ),
                    ),
                ),
            )

            assertEquals(2, result.dexFiles)
            ZipFile(output).use { zip ->
                assertTrue(descriptor(firstMapped) in readDescriptors(zip, "classes.dex"))
                assertTrue(descriptor(secondMapped) in readDescriptors(zip, "classes2.dex"))
                val payloadEntry = zip.getEntry(PAYLOAD_ENTRY)
                assertEquals(PAYLOAD_COMMENT, payloadEntry.comment)
                assertArrayEquals(payload, zip.getInputStream(payloadEntry).readBytes())
            }
        }
    }

    @Test
    fun processZipAggregatesProtoXmlReplacementsIncludingNavigation() {
        withTempDirectory { directory ->
            val input = File(directory, "input.aab")
            val output = File(directory, "output.aab")
            val original = "com.example.feature.DetailFragment"
            val renamed = "a.b.Detail"
            val xml = protoXmlWithClassReference(original)
            createXmlZip(
                input,
                mapOf(
                    "base/manifest/AndroidManifest.xml" to xml,
                    "base/res/navigation/main.xml" to xml,
                ),
            )

            val result = ZipPostR8RenameProcessor.processZip(
                input,
                output,
                ZipPostR8RenameProcessor.Config(
                    componentMapping = RenameMapping.fromForward(mapOf(original to renamed)),
                ),
            )

            assertEquals(2, result.xmlFiles)
            assertEquals(2, result.xmlReplacementCount)
            assertEquals(0, result.xmlFailureCount)
            assertEquals(1, result.componentManifestFiles)
            assertEquals(1, result.layoutFiles)
            ZipFile(output).use { zip ->
                listOf(
                    "base/manifest/AndroidManifest.xml",
                    "base/res/navigation/main.xml",
                ).forEach { name ->
                    val rewritten = zip.getInputStream(zip.getEntry(name)).readBytes()
                    assertEquals(
                        renamed,
                        Resources.XmlNode.parseFrom(rewritten).element.getAttribute(0).value,
                    )
                }
            }
        }
    }

    @Test
    fun processZipReportsParseFailureAndStrictModeFails() {
        withTempDirectory { directory ->
            val input = File(directory, "input.aab")
            val output = File(directory, "output.aab")
            val strictOutput = File(directory, "strict-output.aab")
            createXmlZip(
                input,
                mapOf("base/res/layout/broken.xml" to byteArrayOf(0x0A, 0x05, 0x01)),
            )
            val mapping = RenameMapping.fromForward(mapOf("com.example.View" to "a.b.C"))

            val result = ZipPostR8RenameProcessor.processZip(
                input,
                output,
                ZipPostR8RenameProcessor.Config(viewMapping = mapping),
            )

            assertEquals(1, result.xmlFailureCount)
            assertEquals(
                ProtoXmlViewClassReplacer.FormatStatus.PARSE_FAILED,
                result.xmlFailures.single().formatStatus,
            )

            val strictInput = File(directory, "strict-input.aab")
            createXmlZip(
                strictInput,
                mapOf(
                    "base/res/layout/broken.xml" to
                        (byteArrayOf(0x0A, 0x05, 0x01) + "com.example.View".toByteArray()),
                ),
            )
            assertFailsWithMessage("base/res/layout/broken.xml") {
                ZipPostR8RenameProcessor.processZip(
                    strictInput,
                    strictOutput,
                    ZipPostR8RenameProcessor.Config(
                        viewMapping = mapping,
                        axmlStrictMode = true,
                    ),
                )
            }
        }
    }

    @Test
    fun processZipStrictModeAllowsUnsupportedXmlWithoutResidualNames() {
        withTempDirectory { directory ->
            val input = File(directory, "input.aab")
            val output = File(directory, "output.aab")
            createXmlZip(
                input,
                mapOf("base/res/layout/sdk.xml" to byteArrayOf(0x0A, 0x05, 0x01)),
            )
            val mapping = RenameMapping.fromForward(mapOf("com.example.View" to "a.b.C"))

            val result = ZipPostR8RenameProcessor.processZip(
                input,
                output,
                ZipPostR8RenameProcessor.Config(
                    viewMapping = mapping,
                    axmlStrictMode = true,
                ),
            )

            assertEquals(1, result.xmlFailureCount)
        }
    }

    @Test
    fun processZipRewritesTextXmlWithOldRuntimeName() {
        withTempDirectory { directory ->
            val input = File(directory, "input.apk")
            val output = File(directory, "output.apk")
            val original = "com.example.feature.LegacyFragment"
            createXmlZip(
                input,
                mapOf(
                    "res/navigation.xml" to
                        """<fragment android:name="$original" />""".toByteArray(),
                ),
            )

            val result = ZipPostR8RenameProcessor.processZip(
                input,
                output,
                ZipPostR8RenameProcessor.Config(
                    componentMapping = RenameMapping.fromForward(mapOf(original to "a.b.C")),
                ),
            )

            assertEquals(0, result.xmlFailureCount)
            assertEquals(1, result.layoutFiles)
            val rewritten = readZipEntry(output, "res/navigation.xml")
            assertTrue(rewritten.toString(Charsets.UTF_8).contains("a.b.C"))
        }
    }

    @Test
    fun processZipDoesNotTreatShortRuntimeNameSubstringAsResidual() {
        withTempDirectory { directory ->
            val input = File(directory, "input.apk")
            val output = File(directory, "output.apk")
            createXmlZip(
                input,
                mapOf("res/raw.xml" to "<value>rg.ab</value>".toByteArray()),
            )

            val result = ZipPostR8RenameProcessor.processZip(
                input,
                output,
                ZipPostR8RenameProcessor.Config(
                    componentMapping = RenameMapping.fromForward(mapOf("rg.a" to "x.y")),
                ),
            )

            assertEquals(0, result.xmlFailureCount)
            assertEquals(
                "<value>rg.ab</value>",
                readZipEntry(output, "res/raw.xml").toString(Charsets.UTF_8),
            )
        }
    }

    private fun createInputZip(
        input: File,
        firstDex: ByteArray,
        secondDex: ByteArray,
        payload: ByteArray,
    ) {
        ZipOutputStream(FileOutputStream(input)).use { zip ->
            writeStoredEntry(zip, "classes.dex", firstDex)
            writeStoredEntry(zip, "classes2.dex", secondDex)
            zip.putNextEntry(
                ZipEntry(PAYLOAD_ENTRY).apply {
                    time = PAYLOAD_TIME
                    comment = PAYLOAD_COMMENT
                    method = ZipEntry.DEFLATED
                },
            )
            zip.write(payload)
            zip.closeEntry()
        }
    }

    private fun createXmlZip(input: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(input)).use { zip ->
            entries.forEach { (name, bytes) -> writeStoredEntry(zip, name, bytes) }
        }
    }

    private fun protoXmlWithClassReference(className: String): ByteArray =
        Resources.XmlNode.newBuilder()
            .setElement(
                Resources.XmlElement.newBuilder()
                    .setName("fragment")
                    .addAttribute(
                        Resources.XmlAttribute.newBuilder()
                            .setName("name")
                            .setValue(className),
                    ),
            )
            .build()
            .toByteArray()

    private fun assertFailsWithMessage(expected: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected failure containing '$expected'", failure != null)
        assertTrue(
            "Expected '${failure?.message}' to contain '$expected'",
            failure?.message.orEmpty().contains(expected),
        )
    }

    private fun writeStoredEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        zip.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            },
        )
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readDescriptors(zip: ZipFile, entryName: String): Set<String> {
        val bytes = zip.getInputStream(zip.getEntry(entryName)).readBytes()
        val dex = DexBackedDexFile.fromInputStream(
            Opcodes.getDefault(),
            ByteArrayInputStream(bytes),
        )
        return dex.classes.mapTo(linkedSetOf()) { it.type }
    }

    private fun readZipEntry(zipFile: File, entryName: String): ByteArray =
        ZipFile(zipFile).use { zip ->
            zip.getInputStream(zip.getEntry(entryName)).readBytes()
        }

    private fun buildDex(classDescriptor: String): ByteArray {
        val classDef = ImmutableClassDef(
            classDescriptor,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        val pool = DexPool(Opcodes.getDefault())
        pool.internClass(classDef)
        return MemoryDataStore().also(pool::writeTo).data
    }

    private fun descriptor(className: String): String = "L${className.replace('.', '/')};"

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("zip-post-r8-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val PAYLOAD_ENTRY = "assets/large-payload.bin"
        const val PAYLOAD_COMMENT = "streamed-payload"
        const val PAYLOAD_TIME = 1_600_000_000_000L
    }
}
