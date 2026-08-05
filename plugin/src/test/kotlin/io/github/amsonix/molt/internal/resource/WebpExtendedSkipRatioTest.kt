package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.resource.ImageOutcome.SKIPPED_WEBP_EXTENDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpExtendedSkipRatioTest {

    @Test
    fun fromRecords_computesRatio() {
        val records = listOf(
            ImageProcessRecord("a.webp", "md5a", null, SKIPPED_WEBP_EXTENDED),
            ImageProcessRecord("b.webp", "md5b", "md5c", ImageOutcome.PROCESSED),
        )
        val result = WebpExtendedSkipRatio.fromRecords(records)
        assertEquals(2, result.totalRecords)
        assertEquals(1, result.skippedWebpExtended)
        assertEquals(0.5, result.ratio, 0.0001)
    }

    @Test
    fun assertWithinThreshold_passesWhenBelowMax() {
        val result = WebpExtendedSkipRatio.Result(100, 3, 0.03)
        assertNull(WebpExtendedSkipRatio.assertWithinThreshold(result, 0.05))
    }

    @Test
    fun assertWithinThreshold_failsWhenAboveMax() {
        val result = WebpExtendedSkipRatio.Result(100, 10, 0.10)
        val violation = WebpExtendedSkipRatio.assertWithinThreshold(result, 0.05)
        assertTrue(violation!!.contains("exceeds max=0.05"))
    }
}
