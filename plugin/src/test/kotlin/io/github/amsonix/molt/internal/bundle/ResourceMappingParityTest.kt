package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResourceMappingParityTest {

    @Test
    fun parseStats_countsRenameSections() {
        val mapping = writeMapping(
            """
            res dir mapping:
            	res -> res/a1

            res id mapping:
            	0x7f010001 : drawable/icon -> a2
            	0x7f010002 : string/title -> s1

            res entries path mapping:
            	0x7f010001 : res/drawable/icon.png -> res/a1/a2.png
            """.trimIndent(),
        )
        val stats = ResourceMappingParity.parseStats(mapping)
        assertEquals(1, stats.renamedDirs)
        assertEquals(2, stats.renamedEntries)
        assertEquals(1, stats.renamedPaths)
    }

    @Test
    fun compare_passesWhenEntryCountsAreClose() {
        val apk = writeMapping(
            """
            res dir mapping:
            	res -> res/a1
            res id mapping:
            	0x1 : drawable/a -> x
            	0x2 : drawable/b -> y
            res entries path mapping:
            """.trimIndent(),
        )
        val aab = writeMapping(
            """
            res dir mapping:
            	res -> res/b1
            res id mapping:
            	0x1 : com.app.R.drawable.a -> com.app.R.x
            	0x2 : com.app.R.drawable.b -> com.app.R.y
            	0x3 : com.app.R.drawable.c -> com.app.R.z
            res entries path mapping:
            """.trimIndent(),
        )
        val result = ResourceMappingParity.compare(apk, aab, tolerance = 0.34)
        assertTrue(result.entryWithinTolerance)
        assertTrue(result.withinTolerance)
    }

    @Test
    fun compare_failsWhenEntryCountsDrift() {
        val apk = writeMapping(
            """
            res id mapping:
            	0x1 : drawable/a -> x
            """.trimIndent(),
        )
        val aab = writeMapping(
            """
            res id mapping:
            	0x1 : drawable/a -> x
            	0x2 : drawable/b -> y
            	0x3 : drawable/c -> z
            	0x4 : drawable/d -> w
            """.trimIndent(),
        )
        val result = ResourceMappingParity.compare(apk, aab, tolerance = 0.15)
        assertTrue(!result.entryWithinTolerance)
    }

    private fun writeMapping(content: String): File =
        Files.createTempFile("mapping-parity-", ".txt").toFile().also { file ->
            file.writeText(content)
            file.deleteOnExit()
        }
}
