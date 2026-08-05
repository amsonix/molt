package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildToolsVersionOrderTest {

    @Test
    fun compare_usesNumericSegments() {
        assertTrue(BuildToolsVersionOrder.compare("36.1.0", "9.0.0") > 0)
        assertTrue(BuildToolsVersionOrder.compare("35.0.1", "35.0.0") > 0)
    }

    @Test
    fun compare_prefersStableThenNewestRc() {
        assertTrue(BuildToolsVersionOrder.compare("36.1.0", "36.1.0-rc2") > 0)
        assertTrue(BuildToolsVersionOrder.compare("36.1.0-rc2", "36.1.0-rc1") > 0)
    }

    @Test
    fun parse_handlesNonNumericDirectory() {
        assertEquals(listOf(0), BuildToolsVersionOrder.parse("preview"))
    }
}
