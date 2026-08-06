package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.keep.KeepXmlParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class ResourceObfuscatorTest {

    @Test
    fun resourceTypeFromPath_returnsBaseTypeForQualifierDirectories() {
        assertEquals("layout", ResourceObfuscator.resourceTypeFromPath("layout-night/player.xml"))
        assertEquals("drawable", ResourceObfuscator.resourceTypeFromPath("drawable-xxhdpi/cover.png"))
        assertEquals("mipmap", ResourceObfuscator.resourceTypeFromPath("mipmap-anydpi-v26/ic_launcher.xml"))
    }

    @Test
    fun obfuscateResTree_keepsQualifierDirectoryWhenRenamingLayout() = withResourceTree { input, output ->
        File(input, "layout-night/player.xml").apply {
            parentFile.mkdirs()
            writeText("<FrameLayout />")
        }

        val result = ResourceObfuscator.obfuscateResTree(
            inputResDir = input,
            outputResDir = output,
            keepRules = emptyList(),
            config = config(renameXmlFiles = true, imageAntiDetect = false),
        )

        val renamed = result.xmlRenameMapping.getValue("layout/player")
        assertTrue(File(output, "layout-night/$renamed.xml").isFile)
        assertFalse(File(output, "layout/$renamed.xml").exists())
    }

    @Test
    fun obfuscateResTree_keepsDeclaredLayoutWhileRenamingOthers() = withResourceTree { input, output ->
        File(input, "layout/base.xml").apply {
            parentFile.mkdirs()
            writeText("<FrameLayout />")
        }
        File(input, "layout/google.xml").writeText("<FrameLayout />")

        val keepRules = KeepXmlParser.parseKeepXml(
            """
            <resources xmlns:tools="http://schemas.android.com/tools"
                tools:keep="@layout/base" />
            """.trimIndent(),
        )

        val result = ResourceObfuscator.obfuscateResTree(
            inputResDir = input,
            outputResDir = output,
            keepRules = keepRules,
            config = config(renameXmlFiles = true, imageAntiDetect = false),
        )

        assertTrue(File(output, "layout/base.xml").isFile)
        assertFalse(File(output, "layout/google.xml").exists())
        assertTrue(result.xmlRenameMapping.containsKey("layout/google"))
        assertFalse(result.xmlRenameMapping.containsKey("layout/base"))
    }

    @Test
    fun obfuscateResTree_processesNinePatchAndSimpleWebp() = withResourceTree { input, output ->
        val ninePatch = File(input, "drawable-xxhdpi/panel.9.png")
        val ninePatchBytes = writePng(ninePatch)
        val webp = File(input, "mipmap-anydpi-v26/launcher.webp").apply {
            parentFile.mkdirs()
            writeBytes(buildSimpleVp8Webp())
        }

        val result = ResourceObfuscator.obfuscateResTree(
            inputResDir = input,
            outputResDir = output,
            keepRules = emptyList(),
            config = config(),
        )

        val outNinePatch = File(output, "drawable-xxhdpi/panel.9.png")
        val outWebp = File(output, "mipmap-anydpi-v26/launcher.webp")
        assertFalse(ninePatchBytes.contentEquals(outNinePatch.readBytes()))
        assertFalse(webp.readBytes().contentEquals(outWebp.readBytes()))
        assertEquals(2, result.processedImageCount)
        assertEquals(0, result.skippedImageCount)
    }

    @Test
    fun obfuscateResTree_keepsProcessedPngDecodableAndPixelIdentical() = withResourceTree { input, output ->
        val inputPng = File(input, "drawable-xxhdpi/cover.png")
        val inputBytes = writePng(inputPng)
        val before = ImageIO.read(inputPng)

        val result = ResourceObfuscator.obfuscateResTree(
            inputResDir = input,
            outputResDir = output,
            keepRules = emptyList(),
            config = config(),
        )

        val outputPng = File(output, "drawable-xxhdpi/cover.png")
        assertTrue(outputPng.isFile)
        assertFalse(inputBytes.contentEquals(outputPng.readBytes()))
        val after = ImageIO.read(outputPng)
        assertNotNull(before)
        assertNotNull(after)
        assertArrayEquals(pixelGrid(before!!), pixelGrid(after!!))
        assertEquals(1, result.processedImageCount)
        assertEquals(0, result.skippedImageCount)
    }

    @Test
    fun obfuscateResTree_injectsXmlJunkCommentForLayout() = withResourceTree { input, output ->
        File(input, "layout/page.xml").apply {
            parentFile.mkdirs()
            writeText("<FrameLayout><TextView/></FrameLayout>")
        }

        ResourceObfuscator.obfuscateResTree(
            inputResDir = input,
            outputResDir = output,
            keepRules = emptyList(),
            config = config(injectXmlJunk = true),
        )

        val content = File(output, "layout/page.xml").readText()
        assertTrue(content.contains("shell-obfuscate-junk:"))
    }

    @Test
    fun obfuscateResTree_writesDrawableDirectlyUnderOutputRoot() = withResourceTree { input, output ->
        val inputPng = File(input, "drawable-xxhdpi/cover.png")
        writePng(inputPng)

        ResourceObfuscator.obfuscateResTree(
            inputResDir = input,
            outputResDir = output,
            keepRules = emptyList(),
            config = config(),
        )

        assertTrue(File(output, "drawable-xxhdpi/cover.png").isFile)
        assertFalse(File(output, "res/drawable-xxhdpi/cover.png").exists())
    }

    @Test
    fun obfuscateResTrees_keepsEarlierRootsAndSharesQualifierMapping() {
        val root = Files.createTempDirectory("resource-obfuscator-multi").toFile()
        try {
            val main = File(root, "main").apply { mkdirs() }
            val flavor = File(root, "flavor").apply { mkdirs() }
            val output = File(root, "output")
            File(main, "layout/page.xml").apply {
                parentFile.mkdirs()
                writeText("<FrameLayout android:tag=\"main\" />")
            }
            File(flavor, "layout-land/page.xml").apply {
                parentFile.mkdirs()
                writeText("<FrameLayout android:tag=\"flavor\" />")
            }

            val result = ResourceObfuscator.obfuscateResTrees(
                inputResDirs = listOf(main, flavor),
                outputResDir = output,
                keepRules = emptyList(),
                config = config(renameXmlFiles = true, imageAntiDetect = false),
            )

            val renamed = result.xmlRenameMapping.getValue("layout/page")
            assertTrue(File(output, "layout/$renamed.xml").isFile)
            assertTrue(File(output, "layout-land/$renamed.xml").isFile)
            assertEquals(1, result.xmlRenameMapping.size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun config(
        renameXmlFiles: Boolean = false,
        imageAntiDetect: Boolean = true,
        injectXmlJunk: Boolean = false,
    ) = ResourceObfuscator.Config(
        seed = 7,
        renameXmlFiles = renameXmlFiles,
        injectXmlJunk = injectXmlJunk,
        imageAntiDetect = imageAntiDetect,
        imageMicroCompress = true,
        imagePngMicroCompress = false,
        imageJpegMicroCompress = true,
        imageMicroCompressQuality = 0.97f,
        metadataScope = "com.test/app",
    )

    private fun buildSimpleVp8Webp(): ByteArray {
        val vp8Payload = byteArrayOf(
            0x10, 0x02, 0x00,
            0x9D.toByte(), 0x01, 0x2A,
            0x01, 0x00,
            0x01, 0x00,
        )
        val body = java.io.ByteArrayOutputStream()
        body.write("VP8 ".encodeToByteArray())
        writeIntLe(body, vp8Payload.size)
        body.write(vp8Payload)
        val bodyBytes = body.toByteArray()
        val output = java.io.ByteArrayOutputStream(bodyBytes.size + 12)
        output.write("RIFF".encodeToByteArray())
        writeIntLe(output, bodyBytes.size + 4)
        output.write("WEBP".encodeToByteArray())
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun writeIntLe(target: java.io.ByteArrayOutputStream, value: Int) {
        target.write(value and 0xFF)
        target.write((value shr 8) and 0xFF)
        target.write((value shr 16) and 0xFF)
        target.write((value shr 24) and 0xFF)
    }

    private fun pixelGrid(image: BufferedImage): IntArray =
        IntArray(image.width * image.height) { index ->
            image.getRGB(index % image.width, index / image.width)
        }

    private fun writePng(file: File): ByteArray {
        file.parentFile.mkdirs()
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        repeat(image.width) { x ->
            repeat(image.height) { y ->
                image.setRGB(x, y, 0xFF336699.toInt())
            }
        }
        check(ImageIO.write(image, "png", file))
        return file.readBytes()
    }
    private fun withResourceTree(block: (input: File, output: File) -> Unit) {
        val root = Files.createTempDirectory("resource-obfuscator").toFile()
        try {
            val input = File(root, "input").apply { mkdirs() }
            block(input, File(root, "output"))
        } finally {
            root.deleteRecursively()
        }
    }
}
