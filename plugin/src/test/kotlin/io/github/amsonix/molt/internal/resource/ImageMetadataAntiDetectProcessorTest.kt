package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.bundle.IntegrationTestAssumptions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO

class ImageMetadataAntiDetectProcessorTest {

    @Test
    fun injectPngText_changesBytesButPreservesPixels() {
        val input = Files.createTempFile("meta-png", ".png").toFile()
        val output = Files.createTempFile("meta-png-out", ".png").toFile()
        try {
            writeSolidPng(input)
            val before = ImageIO.read(input)
            val inputBytes = input.readBytes()

            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input,
                    output,
                    Random(7),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(
                        pngMicroCompress = false,
                        metadataToken = "app/v1/seed=7",
                    ),
                ),
            )

            assertFalse(inputBytes.contentEquals(output.readBytes()))
            val after = ImageIO.read(output)
            assertNotNull(before)
            assertNotNull(after)
            assertArrayEquals(pixelGrid(before!!), pixelGrid(after!!))
            assertTrue(ImageDecodeVerifier.verifyDecodable(output.readBytes(), output.name))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun injectNinePatchPng_changesBytesOnly() {
        val input = Files.createTempFile("meta-nine", ".9.png").toFile()
        val output = Files.createTempFile("meta-nine-out", ".9.png").toFile()
        try {
            writeSolidPng(input)
            val inputBytes = input.readBytes()
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input,
                    output,
                    Random(5),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(metadataToken = "nine-patch"),
                ),
            )
            assertFalse(inputBytes.contentEquals(output.readBytes()))
            assertTrue(ImageDecodeVerifier.verifyDecodable(output.readBytes(), output.name))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun injectJpegCom_changesBytesButPreservesPixels() {
        val input = Files.createTempFile("meta-jpg", ".jpg").toFile()
        val output = Files.createTempFile("meta-jpg-out", ".jpg").toFile()
        try {
            writeSolidJpeg(input)
            val before = ImageIO.read(input)
            val inputBytes = input.readBytes()

            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input,
                    output,
                    Random(11),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(
                        jpegMicroCompress = false,
                        jpegMetadataMode = ImageMetadataAntiDetectProcessor.JpegMetadataMode.BOTH,
                        metadataToken = "token-a",
                    ),
                ),
            )

            assertFalse(inputBytes.contentEquals(output.readBytes()))
            val after = ImageIO.read(output)
            assertNotNull(before)
            assertNotNull(after)
            assertArrayEquals(pixelGrid(before!!), pixelGrid(after!!))
            assertTrue(ImageDecodeVerifier.verifyDecodable(output.readBytes(), output.name))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun microCompress_jpeg_changesBytesAndRemainsDecodable() {
        val input = Files.createTempFile("meta-jpg-micro", ".jpg").toFile()
        val output = Files.createTempFile("meta-jpg-micro-out", ".jpg").toFile()
        try {
            writeSolidJpeg(input)
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input,
                    output,
                    Random(19),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(jpegMicroCompress = true),
                ),
            )
            assertFalse(input.readBytes().contentEquals(output.readBytes()))
            assertNotNull(ImageIO.read(output))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun injectWebpXmp_minimalIntoExistingVp8xContainer() {
        val input = Files.createTempFile("meta-webp", ".webp").toFile()
        val output = Files.createTempFile("meta-webp-out", ".webp").toFile()
        try {
            input.writeBytes(buildVp8xWebp())
            assertTrue(ImageMetadataAntiDetectProcessor.process(input, output, Random(3)))
            val outputBytes = output.readBytes()
            assertFalse(input.readBytes().contentEquals(outputBytes))
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("XMP "))
            assertTrue(ImageDecodeVerifier.verifyDecodable(outputBytes, output.name))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun injectWebpXmp_wrapsSimpleVp8Container() {
        val input = Files.createTempFile("meta-webp-simple", ".webp").toFile()
        val output = Files.createTempFile("meta-webp-simple-out", ".webp").toFile()
        try {
            input.writeBytes(buildSimpleVp8Webp())
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input,
                    output,
                    Random(3),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(metadataToken = "simple-webp"),
                ),
            )
            assertFalse(input.readBytes().contentEquals(output.readBytes()))
            val outputBytes = output.readBytes()
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("VP8X"))
            assertWebpVp8xHasXmpFlag(outputBytes)
            assertWebpCanvasSize(outputBytes, width = 1, height = 1)
            assertTrue(ImageDecodeVerifier.verifyDecodable(outputBytes, output.name))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun injectWebpXmp_realLossyWebp_preservesCanvasSize() {
        val fixture = realLossyWebpFixture() ?: return
        val output = Files.createTempFile("meta-webp-real-out", ".webp").toFile()
        try {
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    fixture,
                    output,
                    Random(3),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(metadataToken = "bg-task-page"),
                ),
            )
            assertFalse(fixture.readBytes().contentEquals(output.readBytes()))
            val outputBytes = output.readBytes()
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("XMP "))
            assertWebpVp8xHasXmpFlag(outputBytes)
            assertWebpCanvasSize(outputBytes, width = 1125, height = 1260)
            assertTrue(ImageDecodeVerifier.verifyDecodable(outputBytes, output.name))
        } finally {
            output.delete()
        }
    }

    @Test
    fun processBytes_webpApkFallback_preservesCanvasSize() {
        val fixture = realLossyWebpFixture() ?: return
        val inputBytes = fixture.readBytes()
        val patched = ImageMetadataAntiDetectProcessor.processBytes(
            entryName = "res/drawable-xxhdpi/bg_task_page.webp",
            bytes = inputBytes,
            random = Random(3),
            config = ImageMetadataAntiDetectProcessor.ProcessConfig(
                pngMicroCompress = false,
                jpegMicroCompress = false,
                metadataToken = "apk-fallback-test",
            ),
        )
        assertNotNull(patched)
        assertWebpVp8xHasXmpFlag(patched!!)
        assertWebpCanvasSize(patched, width = 1125, height = 1260)
        assertTrue(ImageDecodeVerifier.verifyDecodable(patched, "bg_task_page.webp"))
    }

    @Test
    fun injectWebpPrivateChunk_intoVp8lOnlyContainer() {
        val fixture = IntegrationTestAssumptions.assumeRepoFile(
            "foundation/res/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.webp",
        )
        val output = Files.createTempFile("meta-webp-vp8l-out", ".webp").toFile()
        try {
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    fixture,
                    output,
                    Random(3),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(metadataToken = "vp8l-private"),
                ),
            )
            val outputBytes = output.readBytes()
            assertFalse(fixture.readBytes().contentEquals(outputBytes))
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("sObf"))
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("shell:token="))
            assertTrue(ImageDecodeVerifier.verifyDecodable(outputBytes, output.name))
        } finally {
            output.delete()
        }
    }

    @Test
    fun injectWebpPrivateChunk_intoVp8AndVp8lMixedContainer() {
        val vp8Payload = byteArrayOf(
            0x10, 0x02, 0x00,
            0x9D.toByte(), 0x01, 0x2A,
            0x01, 0x00,
            0x01, 0x00,
        )
        val mixedWebp = buildWebp(listOf("VP8 " to vp8Payload, "VP8L" to byteArrayOf(0x2F, 0x00, 0x00, 0x00)))
        assertTrue(ImageMetadataAntiDetectProcessor.isIntentionalWebpSkip(mixedWebp))
        val output = Files.createTempFile("meta-webp-mixed-out", ".webp").toFile()
        val input = Files.createTempFile("meta-webp-mixed", ".webp").toFile()
        try {
            input.writeBytes(mixedWebp)
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input,
                    output,
                    Random(3),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(metadataToken = "mixed-tier-c"),
                ),
            )
            val outputBytes = output.readBytes()
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("sObf"))
            assertTrue(ImageDecodeVerifier.verifyDecodable(outputBytes, output.name))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun injectWebpAppendPrivateChunk_animContainerUsesTierDFallback() {
        val vp8x = byteArrayOf(
            0x10, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00,
            0x00, 0x00, 0x00,
        )
        val animWebp = buildWebp(
            listOf(
                "VP8X" to vp8x,
                "ANIM" to byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
                "VP8L" to byteArrayOf(0x2F, 0x0F, 0x00, 0x00, 0x00),
            ),
        )
        assertTrue(ImageMetadataAntiDetectProcessor.isIntentionalWebpSkip(animWebp))
        val input = Files.createTempFile("meta-webp-anim", ".webp").toFile()
        val output = Files.createTempFile("meta-webp-anim-out", ".webp").toFile()
        try {
            input.writeBytes(animWebp)
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input,
                    output,
                    Random(3),
                    ImageMetadataAntiDetectProcessor.ProcessConfig(metadataToken = "anim-tier-d"),
                ),
            )
            val outputBytes = output.readBytes()
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("sObf"))
            assertTrue(ImageDecodeVerifier.verifyDecodable(outputBytes, output.name))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun metadataToken_isDeterministicForSameScopeAndPath() {
        val tokenA = ResourceObfuscator.buildMetadataToken("com.app/a", "drawable/icon.png", 7)
        val tokenB = ResourceObfuscator.buildMetadataToken("com.app/a", "drawable/icon.png", 7)
        val tokenC = ResourceObfuscator.buildMetadataToken("com.app/b", "drawable/icon.png", 7)
        assertTrue(tokenA == tokenB)
        assertFalse(tokenA == tokenC)
    }

    private fun writeSolidPng(file: File) {
        val image = BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB)
        repeat(image.width) { x ->
            repeat(image.height) { y ->
                image.setRGB(x, y, 0xFF112233.toInt())
            }
        }
        check(ImageIO.write(image, "png", file))
    }

    private fun writeSolidJpeg(file: File) {
        val image = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)
        repeat(image.width) { x ->
            repeat(image.height) { y ->
                image.setRGB(x, y, 0xFF445566.toInt())
            }
        }
        check(ImageIO.write(image, "jpg", file))
    }

    private fun pixelGrid(image: BufferedImage): IntArray =
        IntArray(image.width * image.height) { index ->
            image.getRGB(index % image.width, index / image.width)
        }

    private fun buildSimpleVp8Webp(): ByteArray {
        val vp8Payload = byteArrayOf(
            0x10, 0x02, 0x00,
            0x9D.toByte(), 0x01, 0x2A,
            0x01, 0x00,
            0x01, 0x00,
        )
        return buildWebp(listOf("VP8 " to vp8Payload))
    }

    private fun buildVp8xWebp(): ByteArray {
        val vp8xFlags = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val vp8Payload = byteArrayOf(
            0x10, 0x02, 0x00,
            0x9D.toByte(), 0x01, 0x2A,
            0x01, 0x00,
            0x01, 0x00,
        )
        return buildWebp(listOf("VP8X" to vp8xFlags, "VP8 " to vp8Payload))
    }

    private fun realLossyWebpFixture(): File? {
        val candidates = listOf(
            File("src/test/resources/fixtures/bg_task_page.webp"),
            File("../../foundation/res/src/main/res/drawable-xxhdpi/bg_task_page.webp"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun assertWebpVp8xHasXmpFlag(bytes: ByteArray) {
        val vp8x = findWebpChunk(bytes, "VP8X")
        assertNotNull(vp8x)
        assertTrue((vp8x!![0].toInt() and 0x08) != 0)
    }

    private fun assertWebpCanvasSize(bytes: ByteArray, width: Int, height: Int) {
        val vp8x = findWebpChunk(bytes, "VP8X")
        assertNotNull(vp8x)
        val actualWidth = 1 + readInt24Le(vp8x!!, 4)
        val actualHeight = 1 + readInt24Le(vp8x, 7)
        assertTrue("width=$actualWidth expected=$width", actualWidth == width)
        assertTrue("height=$actualHeight expected=$height", actualHeight == height)
    }

    private fun findWebpChunk(bytes: ByteArray, fourCC: String): ByteArray? {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val tag = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readIntLe(bytes, offset + 4)
            val dataStart = offset + 8
            val dataEnd = dataStart + size
            if (dataEnd > bytes.size) return null
            if (tag == fourCC) return bytes.copyOfRange(dataStart, dataEnd)
            val paddedSize = if (size and 1 == 1) size + 1 else size
            offset = dataStart + paddedSize
        }
        return null
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readInt24Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    private fun buildWebp(chunks: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        chunks.forEach { (fourCC, data) ->
            body.write(fourCC.encodeToByteArray())
            writeIntLe(body, data.size)
            body.write(data)
            if (data.size and 1 == 1) body.write(0)
        }
        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream(bodyBytes.size + 12)
        output.write("RIFF".encodeToByteArray())
        writeIntLe(output, bodyBytes.size + 4)
        output.write("WEBP".encodeToByteArray())
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun writeIntLe(target: ByteArrayOutputStream, value: Int) {
        target.write(value and 0xFF)
        target.write((value shr 8) and 0xFF)
        target.write((value shr 16) and 0xFF)
        target.write((value shr 24) and 0xFF)
    }
}
