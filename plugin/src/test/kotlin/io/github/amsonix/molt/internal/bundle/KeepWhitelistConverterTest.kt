package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.keep.KeepXmlParser
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepWhitelistConverterTest {

    @Test
    fun convertsKeepResourceToResChiperRule() {
        val rules = KeepWhitelistConverter.fromKeepResources(
            listOf(
                KeepXmlParser.KeepResource("layout", "home_loading"),
                KeepXmlParser.KeepResource("string", "google_app_id"),
            ),
        )
        assertTrue("*.R.layout.home_loading" in rules)
        assertTrue("*.R.string.google_app_id" in rules)
        assertTrue("res/raw/*" in rules)
    }
}
