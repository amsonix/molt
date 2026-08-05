package io.github.amsonix.molt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceKeepMergerTest {

    @Test
    fun mergeShellKeepEntries_withoutDynamicKeepStillIncludesSafetyRules() {
        val declared = ResourceKeepResource("layout", "user_screen")

        val merged = ResourceKeepMerger.mergeShellKeepEntries(declared = listOf(declared))

        assertTrue(declared in merged)
        assertTrue(ResourceKeepResource("string", "project_id") in merged)
        assertTrue(ResourceKeepResource("drawable", "tt_*") in merged)
    }

    @Test
    fun mergeShellKeepEntries_includesOptionalDynamicEntries() {
        val dynamic = ResourceKeepResource("raw", "detected_at_build_time")

        val merged = ResourceKeepMerger.mergeShellKeepEntries(
            declared = emptyList(),
            dynamic = listOf(dynamic),
        )

        assertTrue(dynamic in merged)
    }

    @Test
    fun mergeKeepEntries_includesStaticBaselineWhenRequested() {
        val merged = ResourceKeepMerger.mergeKeepEntries(
            declared = emptyList(),
            detected = emptyList(),
            includeBuiltinWildcards = false,
            includeStaticBaseline = true,
        )
        assertTrue(merged.any { it.type == "string" && it.name == "project_id" })
    }

    @Test
    fun compactWithWildcards_dropsExactEntriesCoveredByWildcard() {
        val merged = ResourceKeepMerger.compactWithWildcards(
            listOf(
                ResourceKeepResource("drawable", "tt_*"),
                ResourceKeepResource("drawable", "tt_logo"),
            ),
        )
        assertEquals(listOf(ResourceKeepResource("drawable", "tt_*")), merged)
    }
}
