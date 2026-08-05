package io.github.amsonix.molt.internal.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenameMappingTest {

    @Test
    fun resolve_remapsInnerClassesWithOuterMapping() {
        val mapping = RenameMapping.build(
            candidates = setOf("com.example.app.main.MainActivity"),
            seed = 1,
            excludePatterns = emptyList(),
        )
        val outer = mapping.resolve("com.example.app.main.MainActivity")!!
        assertEquals(
            "$outer\$18",
            mapping.resolve("com.example.app.main.MainActivity\$18"),
        )
    }

    @Test
    fun resolve_returnsNullForUnmappedOuter() {
        val mapping = RenameMapping.build(
            candidates = setOf("com.example.Other"),
            seed = 1,
            excludePatterns = emptyList(),
        )
        assertNull(mapping.resolve("com.example.app.main.MainActivity\$18"))
    }
}
