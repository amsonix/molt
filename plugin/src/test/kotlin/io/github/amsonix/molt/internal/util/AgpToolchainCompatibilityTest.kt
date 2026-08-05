package io.github.amsonix.molt.internal.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AgpToolchainCompatibilityTest {

    @Test
    fun pinnedAapt2MatchesAgp813() {
        assertEquals("8.13.2-14304508", AgpToolchainCompatibility.PINNED_AAPT2_PROTO)
    }
}
