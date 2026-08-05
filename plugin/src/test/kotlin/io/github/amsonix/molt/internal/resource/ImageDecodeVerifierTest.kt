package io.github.amsonix.molt.internal.resource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random

class ImageDecodeVerifierTest {

    @Test
    fun verifyDecodable_acceptsProcessedPngJpegWebp() {
        val pngIn = Files.createTempFile("decode-png", ".png").toFile()
        val pngOut = Files.createTempFile("decode-png-out", ".png").toFile()
        val jpgIn = Files.createTempFile("decode-jpg", ".jpg").toFile()
        val jpgOut = Files.createTempFile("decode-jpg-out", ".jpg").toFile()
        try {
            ImageMetadataAntiDetectProcessorTestSupport.writeSolidPng(pngIn)
            ImageMetadataAntiDetectProcessorTestSupport.writeSolidJpeg(jpgIn)
            assertTrue(ImageMetadataAntiDetectProcessor.process(pngIn, pngOut, Random(1)))
            assertTrue(ImageMetadataAntiDetectProcessor.process(jpgIn, jpgOut, Random(2)))
            assertTrue(ImageDecodeVerifier.verifyDecodable(pngOut.readBytes(), pngOut.name))
            assertTrue(ImageDecodeVerifier.verifyDecodable(jpgOut.readBytes(), jpgOut.name))
        } finally {
            pngIn.delete()
            pngOut.delete()
            jpgIn.delete()
            jpgOut.delete()
        }

        val webpFixture = realLossyWebpFixture() ?: return
        val webpOut = Files.createTempFile("decode-webp-out", ".webp").toFile()
        try {
            assertTrue(ImageMetadataAntiDetectProcessor.process(webpFixture, webpOut, Random(3)))
            assertTrue(ImageDecodeVerifier.verifyDecodable(webpOut.readBytes(), webpOut.name))
        } finally {
            webpOut.delete()
        }
    }

    @Test
    fun verifyDecodable_rejectsCorruptWebpWithEmptyVp8x() {
        val vp8Payload = byteArrayOf(
            0x10, 0x02, 0x00,
            0x9D.toByte(), 0x01, 0x2A,
            0x01, 0x00,
            0x01, 0x00,
        )
        val corrupt = buildWebp(
            listOf(
                "VP8X" to ByteArray(10),
                "XMP " to byteArrayOf(0x3c, 0x3f, 0x78),
                "VP8 " to vp8Payload,
            ),
        )
        assertFalse(ImageDecodeVerifier.verifyDecodable(corrupt, "broken.webp"))
        assertFalse(ImageMetadataAntiDetectProcessor.verifyWebpStructure(corrupt))
    }

    @Test
    fun verifyDecodable_ninePatchPreservesStretchMetadataAfterInject() {
        val fixture = ninePatchFixture() ?: return
        val output = Files.createTempFile("decode-nine-out", ".9.png").toFile()
        try {
            assertTrue(ImageMetadataAntiDetectProcessor.process(fixture, output, Random(4)))
            assertFalse(fixture.readBytes().contentEquals(output.readBytes()))
            assertTrue(ImageDecodeVerifier.verifyDecodable(output.readBytes(), output.name))
        } finally {
            output.delete()
        }
    }

    @Test
    fun process_rejectsOutputThatFailsDecodeCheck() {
        val fixture = realLossyWebpFixture() ?: return
        val output = Files.createTempFile("decode-fail-out", ".webp").toFile()
        try {
            assertTrue(ImageMetadataAntiDetectProcessor.process(fixture, output, Random(5)))
            assertNotNull(output.readBytes())
            assertTrue(ImageDecodeVerifier.verifyDecodable(output.readBytes(), output.name))
        } finally {
            output.delete()
        }
    }

    private fun realLossyWebpFixture(): File? {
        val candidates = listOf(
            File("src/test/resources/fixtures/bg_task_page.webp"),
            File("../../foundation/res/src/main/res/drawable-xxhdpi/bg_task_page.webp"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun ninePatchFixture(): File? {
        val candidates = listOf(
            File("../../foundation/res/src/main/res/drawable-xxhdpi/bg_recall_discount_dialog.9.png"),
            File("../../foundation/res/src/main/res/drawable-xxhdpi/bg_recall_discount_weekly_subscription.9.png"),
        )
        return candidates.firstOrNull { it.isFile }
    }

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

/** 供 [ImageDecodeVerifierTest] 复用的合成图写入。 */
internal object ImageMetadataAntiDetectProcessorTestSupport {
    fun writeSolidPng(file: File) {
        val image = java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        repeat(image.width) { x ->
            repeat(image.height) { y ->
                image.setRGB(x, y, 0xFF112233.toInt())
            }
        }
        check(javax.imageio.ImageIO.write(image, "png", file))
    }

    fun writeSolidJpeg(file: File) {
        val image = java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_RGB)
        repeat(image.width) { x ->
            repeat(image.height) { y ->
                image.setRGB(x, y, 0xFF445566.toInt())
            }
        }
        check(javax.imageio.ImageIO.write(image, "jpg", file))
    }
}
