package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.ArrayDeque
import java.util.Random

class ApkResourcePathTest {

    @Test
    fun parse_qualifierPathPreservesFullDirectoryAndNinePatchSuffix() {
        val path = ApkResourcePath.parse("res/drawable-xxhdpi/foo.9.png")

        assertEquals("res/drawable-xxhdpi", path.directory)
        assertEquals("foo", path.baseName)
        assertEquals(".9.png", path.suffix)
        val directoryMapping = mapOf("res/drawable-xxhdpi" to "res/a1")
        val filePathMapping = mapOf("res/drawable-xxhdpi/foo.9.png" to "res/a1/d7.9.png")
        assertEquals(
            "res/a1/d7.9.png",
            path.remap("res/a1", "d7"),
        )
        assertEquals(
            "res/a1/d7.9.png",
            ApkResourceObfuscateEngine.remapZipEntry(
                "res/drawable-xxhdpi/foo.9.png",
                directoryMapping,
                filePathMapping,
            ),
        )
    }

    @Test
    fun remap_directoryMappingRequiresCompleteQualifierPath() {
        val path = ApkResourcePath.parse("res/drawable-night/banner.webp")

        assertEquals(
            "res/drawable-night/d1.webp",
            path.remap("res/drawable-night", "d1"),
        )
        assertEquals(
            "res/a2/d1.webp",
            path.remap("res/a2", "d1"),
        )
    }

    @Test
    fun parse_supportsAaptOptimizedFlatPath() {
        val path = ApkResourcePath.parse("res/y4.xml")

        assertEquals("res", path.directory)
        assertEquals("y4", path.baseName)
        assertEquals(".xml", path.suffix)
        assertEquals("res/a1/l3.xml", path.remap("res/a1", "l3"))
    }

    @Test
    fun nextUniqueFilePath_avoidsFlattenedQualifierCollision() {
        val path = ApkResourcePath.parse("res/y4.9.png")
        val used = mutableSetOf("res/a1/d7.9.png")

        assertEquals(
            "res/a1/d7_1.9.png",
            ApkResourceObfuscateEngine.nextUniqueFilePath(path, "res/a1", "d7", used),
        )
    }

    @Test
    fun nextUniqueName_retriesCollisionWithinType() {
        val random = SequenceRandom(
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 1, 1, 1,
        )
        val used = mutableSetOf<String>()

        val first = ApkResourceObfuscateEngine.nextUniqueName(random, used, 'a')
        val second = ApkResourceObfuscateEngine.nextUniqueName(random, used, 'a')

        assertEquals("aaa0", first)
        assertEquals("abb1", second)
        assertNotEquals(first, second)
    }

    private class SequenceRandom(vararg values: Int) : Random(0) {
        private val values = ArrayDeque(values.toList())

        override fun nextInt(bound: Int): Int = values.removeFirst() % bound
    }
}
