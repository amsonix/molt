package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.bundle.IntegrationTestAssumptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ApkImageEntryPatcherTest {

    @Test
    fun patchIfNeeded_changesPngBytesInApkEntry() {
        val png = writeSolidPngBytes()
        val patched = ApkImageEntryPatcher.patchIfNeeded(
            entryName = "res/drawable-xxhdpi/a.png",
            bytes = png,
            seed = 9,
            metadataScope = "com.app/release",
            enabled = true,
        )
        assertFalse(png.contentEquals(patched))
        assertTrue(ImageDecodeVerifier.verifyDecodable(patched, "a.png"))
    }

    @Test
    fun patchIfNeeded_webpRemainsDecodable() {
        val fixture = IntegrationTestAssumptions.assumeRepoFile(
            "foundation/res/src/main/res/drawable-xxhdpi/bg_task_page.webp",
        )
        val patched = ApkImageEntryPatcher.patchIfNeeded(
            entryName = "res/aod7/dveh1.webp",
            bytes = fixture.readBytes(),
            seed = 3,
            metadataScope = "com.app/release",
            enabled = true,
        )
        assertFalse(fixture.readBytes().contentEquals(patched))
        assertTrue(ImageDecodeVerifier.verifyDecodable(patched, "dveh1.webp"))
    }

    @Test
    fun patchIfNeeded_skipsAlreadyProcessedPng() {
        val png = writeSolidPngBytes()
        val first = ApkImageEntryPatcher.patchIfNeeded(
            entryName = "res/drawable-xxhdpi/a.png",
            bytes = png,
            seed = 9,
            metadataScope = "com.app/release",
            enabled = true,
        )
        val second = ApkImageEntryPatcher.patchIfNeeded(
            entryName = "res/drawable-xxhdpi/a.png",
            bytes = first,
            seed = 9,
            metadataScope = "com.app/release",
            enabled = true,
        )
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun patchIfNeeded_skipsNonImageEntry() {
        val bytes = "hello".encodeToByteArray()
        val patched = ApkImageEntryPatcher.patchIfNeeded(
            entryName = "classes.dex",
            bytes = bytes,
            seed = 9,
            metadataScope = "com.app/release",
            enabled = true,
        )
        assertTrue(bytes.contentEquals(patched))
    }

    private fun writeSolidPngBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 2, 2)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }
}
