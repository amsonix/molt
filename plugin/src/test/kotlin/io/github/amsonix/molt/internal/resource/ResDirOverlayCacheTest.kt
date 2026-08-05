package io.github.amsonix.molt.internal.resource

import io.github.amsonix.molt.internal.resource.ResourceObfuscator.Config
import io.github.amsonix.molt.internal.keep.KeepXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResDirOverlayCacheTest {

    @Test
    fun overlayConfigHash_changesWhenSeedChanges() {
        val keep = emptyList<KeepXmlParser.KeepResource>()
        val a = ResDirOverlayCache.overlayConfigHash(config(seed = 1), keep)
        val b = ResDirOverlayCache.overlayConfigHash(config(seed = 2), keep)
        assertTrue(a != b)
    }

    @Test
    fun obfuscateIncremental_skipsUnchangedResDir() {
        val root = Files.createTempDirectory("overlay-cache").toFile()
        try {
            val main = File(root, "main").apply { mkdirs() }
            File(main, "drawable/icon.png").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val flavor = File(root, "flavor").apply { mkdirs() }
            File(flavor, "layout/page.xml").apply {
                parentFile.mkdirs()
                writeText("<FrameLayout />")
            }
            val output = File(root, "out")
            val cache = File(root, "cache")
            val keep = emptyList<KeepXmlParser.KeepResource>()
            val cfg = config()

            val first = ResDirOverlayCache.obfuscateIncremental(
                inputResDirs = listOf(main, flavor),
                outputResDir = output,
                cacheRoot = cache,
                keepRules = keep,
                config = cfg,
            )
            val mainOut = File(output, "drawable/icon.png")
            val flavorOut = File(output, "layout/page.xml")
            assertTrue(mainOut.isFile)
            assertTrue(flavorOut.isFile)
            val mainBytes = mainOut.readBytes()

            flavor.resolve("layout/page.xml").writeText("<FrameLayout android:tag=\"v2\" />")
            val second = ResDirOverlayCache.obfuscateIncremental(
                inputResDirs = listOf(main, flavor),
                outputResDir = output,
                cacheRoot = cache,
                keepRules = keep,
                config = cfg,
            )

            assertEquals(mainBytes.toList(), File(output, "drawable/icon.png").readBytes().toList())
            assertTrue(File(output, "layout/page.xml").readText().contains("v2"))
            assertEquals(first.xmlRenameMapping.keys, second.xmlRenameMapping.keys)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fingerprint_changesWhenFileContentChanges() {
        val dir = Files.createTempDirectory("fp").toFile()
        try {
            val file = File(dir, "drawable/a.png").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val before = ResDirFingerprint.of(dir)
            file.writeBytes(byteArrayOf(2))
            val after = ResDirFingerprint.of(dir)
            assertTrue(before != after)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fingerprint_largeFileUsesHeadTailSample() {
        val dir = Files.createTempDirectory("fp-large").toFile()
        try {
            val file = File(dir, "drawable/large.bin").apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(300 * 1024) { 1 })
            }
            val before = ResDirFingerprint.of(dir)
            file.setLastModified(file.lastModified() + 60_000L)
            val afterMtime = ResDirFingerprint.of(dir)
            assertEquals(before, afterMtime)
            file.writeBytes(ByteArray(300 * 1024) { 2 })
            val afterContent = ResDirFingerprint.of(dir)
            assertTrue(before != afterContent)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fingerprint_largeFileDetectsMiddleChange() {
        val dir = Files.createTempDirectory("fp-large-mid").toFile()
        try {
            val file = File(dir, "drawable/large.bin").apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(300 * 1024) { 1 })
            }
            val before = ResDirFingerprint.of(dir)
            java.io.RandomAccessFile(file, "rw").use { access ->
                access.seek(150L * 1024L)
                access.write(2)
            }
            val after = ResDirFingerprint.of(dir)
            assertTrue(before != after)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun config(seed: Int = 7) = Config(
        seed = seed,
        renameXmlFiles = false,
        injectXmlJunk = false,
        imageAntiDetect = false,
        metadataScope = "com.test/app",
    )
}
