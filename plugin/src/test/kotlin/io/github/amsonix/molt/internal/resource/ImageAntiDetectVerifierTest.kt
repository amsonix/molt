package io.github.amsonix.molt.internal.resource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAntiDetectVerifierTest {

    @Test
    fun verifyOverlay_failsWhenProcessedBytesUnchanged() {
        val records = listOf(
            ImageProcessRecord(
                relativePath = "drawable/a.png",
                sourceMd5 = "abc",
                outputMd5 = "abc",
                outcome = ImageOutcome.PROCESSED,
            ),
        )
        val result = ImageAntiDetectVerifier.verifyOverlay(records, failOnUnchanged = true)
        assertFalse(result.success)
        assertTrue(result.unchanged.contains("drawable/a.png"))
    }

    @Test
    fun verifyOverlay_allowsSkippedKeep() {
        val records = listOf(
            ImageProcessRecord(
                relativePath = "drawable/sdk.png",
                sourceMd5 = "abc",
                outputMd5 = null,
                outcome = ImageOutcome.SKIPPED_KEEP,
            ),
        )
        val result = ImageAntiDetectVerifier.verifyOverlay(records, failOnUnchanged = true)
        assertTrue(result.success)
    }

    @Test
    fun verifyOverlay_failsOnSkippedUnsupportedWhenEnabled() {
        val records = listOf(
            ImageProcessRecord(
                relativePath = "drawable/a.png",
                sourceMd5 = "abc",
                outputMd5 = "abc",
                outcome = ImageOutcome.SKIPPED_UNSUPPORTED,
            ),
        )
        val result = ImageAntiDetectVerifier.verifyOverlay(
            records,
            failOnUnchanged = false,
            failOnSkippedUnsupported = true,
        )
        assertFalse(result.success)
        assertTrue(result.unchanged.contains("drawable/a.png"))
    }

    @Test
    fun verifyOverlay_allowsSkippedWebpExtendedWhenUnsupportedFailEnabled() {
        val records = listOf(
            ImageProcessRecord(
                relativePath = "drawable/ic_help1.webp",
                sourceMd5 = "abc",
                outputMd5 = "abc",
                outcome = ImageOutcome.SKIPPED_WEBP_EXTENDED,
            ),
        )
        val result = ImageAntiDetectVerifier.verifyOverlay(
            records,
            failOnUnchanged = false,
            failOnSkippedUnsupported = true,
        )
        assertTrue(result.success)
    }
}
