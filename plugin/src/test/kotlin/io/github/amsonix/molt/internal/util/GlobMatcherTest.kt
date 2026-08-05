package io.github.amsonix.molt.internal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobMatcherTest {

    @Test
    fun matches_resXmlSdkPatterns() {
        assertTrue(GlobMatcher.matches("base/res/aod7/tt_banner.xml", "**/res/**/tt_*.xml"))
        assertFalse(GlobMatcher.matches("base/res/layout/main.xml", "**/res/**/tt_*.xml"))
    }

    @Test
    fun anyMatch_multiplePatterns() {
        val patterns = listOf("**/res/**/mbridge*.xml", "**/res/**/tt_*.xml")
        assertTrue(GlobMatcher.anyMatch("res/foo/mbridge_layout.xml", patterns))
    }
}
