package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.bundle.IntegrationTestAssumptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class ExtendedWebpSkipTest {

    @Test
    fun process_injectsMinimalXmpIntoExistingVp8xHelp1() {
        val fixture = IntegrationTestAssumptions.assumeRepoFile(
            "foundation/res/src/main/res/drawable-xxhdpi/ic_help1.webp",
        )
        val original = fixture.readBytes()
        val output = File.createTempFile("help1-out", ".webp")
        try {
            assertTrue(
                ImageMetadataAntiDetectProcessor.process(
                    input = fixture,
                    output = output,
                    random = Random(3),
                    config = ImageMetadataAntiDetectProcessor.ProcessConfig(metadataToken = "help1"),
                ),
            )
            val outputBytes = output.readBytes()
            assertFalse(original.contentEquals(outputBytes))
            assertTrue(String(outputBytes, Charsets.US_ASCII).contains("XMP "))
            assertTrue(ImageDecodeVerifier.verifyDecodable(outputBytes, output.name))
        } finally {
            output.delete()
        }
    }

    @Test
    fun patchIfNeeded_injectsMinimalXmpIntoExtendedAlphaWebp() {
        val fixture = IntegrationTestAssumptions.assumeRepoFile(
            "foundation/res/src/main/res/drawable-xxhdpi/ic_task_float_enter.webp",
        )
        val original = fixture.readBytes()
        val patched = ApkImageEntryPatcher.patchIfNeeded(
            entryName = "res/aod7/task_float.webp",
            bytes = original,
            seed = -959025531,
            metadataScope = "com.example.app/googleRelease",
            enabled = true,
        )
        assertFalse(original.contentEquals(patched))
        assertTrue(String(patched, Charsets.US_ASCII).contains("XMP "))
        assertTrue(ImageDecodeVerifier.verifyDecodable(patched, "task_float.webp"))
    }
}
