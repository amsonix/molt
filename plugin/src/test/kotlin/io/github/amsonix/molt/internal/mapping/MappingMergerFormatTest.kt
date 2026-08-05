package io.github.amsonix.molt.internal.mapping

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingMergerFormatTest {

    @Test
    fun toProguardMappingLines_sortsEntriesAndEndsWithColon() {
        val lines = MappingMerger.toProguardMappingLines(
            mapOf(
                "com.example.Z" to "z",
                "com.example.A" to "a.b.c",
            ),
        )
        assertEquals(
            listOf(
                "com.example.A -> a.b.c:",
                "com.example.Z -> z:",
            ),
            lines,
        )
        assertTrue(lines.all { it.endsWith(":") })
    }

    @Test
    fun compose_formatsAppendedClassAsProguardHeader() {
        val result = MappingMerger.compose(
            r8Mapping = "# empty R8 mapping",
            shellMapping = RenameMapping.fromForward(
                mapOf("com.example.Missing" to "shell.runtime.Missing"),
            ),
        )

        assertEquals(
            "# empty R8 mapping\ncom.example.Missing -> shell.runtime.Missing:",
            result,
        )
    }
}
