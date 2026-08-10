package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.resource.ImagePatchRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MoltObfuscateTransformVerifyTest {

    @Test
    fun appendImagePatchRecords_mergesOverlayReportAndInjections() {
        val overlay = File.createTempFile("overlay-report", ".txt")
        val output = File.createTempFile("output-report", ".txt")
        try {
            overlay.writeText("# overlay\nres/x/a.png\tPROCESSED\t111\t222\n")
            MoltObfuscateTransformVerify.appendImagePatchRecords(
                overlayReport = overlay,
                outputReport = output,
                records = listOf(
                    ImagePatchRecord("res/y/b.png", "333", "444"),
                    ImagePatchRecord("res/z/c.png", "555", "666"),
                ),
            )
            val lines = output.readLines()
            assertTrue("overlay lines must be preserved", lines.any { it.startsWith("res/x/a.png") })
            assertEquals("transform records must be appended", 3, lines.count { it.contains("\tPROCESSED\t") })
            assertTrue(
                "md5 columns must be readable by readProcessedMd5FromReport",
                output.readText().contains("444") && output.readText().contains("666"),
            )
        } finally {
            overlay.delete()
            output.delete()
        }
    }
}
