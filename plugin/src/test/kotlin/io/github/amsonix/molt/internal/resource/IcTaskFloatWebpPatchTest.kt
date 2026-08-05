package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.bundle.IntegrationTestAssumptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class IcTaskFloatWebpPatchTest {

    @Test
    fun simpleLossyWebp_stillGetsMetadata() {
        val fixture = IntegrationTestAssumptions.assumeRepoFile(
            "foundation/res/src/main/res/drawable-xxhdpi/bg_task_page.webp",
        )
        val original = fixture.readBytes()
        val patched = ApkImageEntryPatcher.patchIfNeeded(
            entryName = "res/aod7/bg_task_page.webp",
            bytes = original,
            seed = -959025531,
            metadataScope = "com.example.app/googleRelease",
            enabled = true,
        )
        assertTrue(patched.size > original.size)
        assertTrue(ImageDecodeVerifier.verifyDecodable(patched, "bg_task_page.webp"))
        assertFalse(original.contentEquals(patched))
    }
}
