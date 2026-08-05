package io.github.amsonix.molt.internal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoltObfuscateConfigValidationTest {

    @Test
    fun requireValidObfuscationMode_acceptsKnownModes() {
        listOf("default", "dir", "file").forEach { mode ->
            assertEquals(mode, requireValidObfuscationMode(mode))
        }
    }

    @Test
    fun requireValidObfuscationMode_rejectsUnknownMode() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireValidObfuscationMode("typo")
        }
        assertEquals(true, error.message?.contains("obfuscationMode"))
    }
}
