package io.github.amsonix.molt.internal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeepXmlDiscoveryTest {

    @Test
    fun keepXmlFilesInResDirs_findsExistingKeepXml() {
        val root = createTempDir()
        val resDir = File(root, "main/res").apply { mkdirs() }
        val keepXml = File(resDir, "raw/keep.xml").apply {
            parentFile.mkdirs()
            writeText("<resources />")
        }

        val found = KeepXmlDiscovery.keepXmlFilesInResDirs(listOf(resDir))

        assertEquals(listOf(keepXml.canonicalFile), found.map { it.canonicalFile })
    }

    @Test
    fun keepXmlFilesInResDirs_ignoresMissingKeepXml() {
        val root = createTempDir()
        val resDir = File(root, "main/res").apply { mkdirs() }

        assertTrue(KeepXmlDiscovery.keepXmlFilesInResDirs(listOf(resDir)).isEmpty())
    }

    @Test
    fun keepXmlFilesInResDirs_deduplicatesSameFile() {
        val root = createTempDir()
        val resDir = File(root, "main/res").apply { mkdirs() }
        val keepXml = File(resDir, "raw/keep.xml").apply {
            parentFile.mkdirs()
            writeText("<resources />")
        }

        val found = KeepXmlDiscovery.keepXmlFilesInResDirs(listOf(resDir, resDir))

        assertEquals(1, found.size)
        assertEquals(keepXml.canonicalFile, found.single().canonicalFile)
    }
}
