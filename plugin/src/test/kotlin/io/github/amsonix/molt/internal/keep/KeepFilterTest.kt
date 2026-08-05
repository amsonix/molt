package io.github.amsonix.molt.internal.keep

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepFilterTest {

    @Test
    fun `wildcard keep rule blocks obfuscation`() {
        val rules = KeepXmlParser.parseKeepXml(
            """
            <resources xmlns:tools="http://schemas.android.com/tools"
                tools:keep="@drawable/mbridge_*,@layout/home_main" />
            """.trimIndent(),
        )
        assertFalse(KeepFilter.shouldObfuscate("drawable", "mbridge_banner", rules))
        assertFalse(KeepFilter.shouldObfuscate("layout", "home_main", rules))
        assertTrue(KeepFilter.shouldObfuscate("layout", "profile_page", rules))
    }
}
