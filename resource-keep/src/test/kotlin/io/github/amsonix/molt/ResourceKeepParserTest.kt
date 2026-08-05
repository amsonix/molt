package io.github.amsonix.molt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceKeepParserTest {

    @Test
    fun parseKeepXml_readsToolsKeepEntries() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources xmlns:tools="http://schemas.android.com/tools"
                tools:keep="
                    @drawable/tt_logo,
                    @layout/mbridge_*,
                    @string/google_app_id" />
        """.trimIndent()

        val entries = ResourceKeepParser.parseKeepXml(xml)

        assertEquals(
            setOf("drawable/tt_logo", "layout/mbridge_*", "string/google_app_id"),
            entries.map { it.toQualifier() }.toSet(),
        )
    }

    @Test
    fun mergeKeepXmlFiles_deduplicatesAcrossFiles() {
        val first = createTempKeep(
            """@drawable/a,
              @string/b""",
        )
        val second = createTempKeep("""@drawable/a, @raw/c""")

        val merged = ResourceKeepParser.mergeKeepXmlFiles(listOf(first, second))

        assertEquals(
            setOf("drawable/a", "string/b", "raw/c"),
            merged.map { it.toQualifier() }.toSet(),
        )
    }

    private fun createTempKeep(body: String): java.io.File =
        java.io.File.createTempFile("keep-", ".xml").also { file ->
            file.writeText(
                """
                <resources xmlns:tools="http://schemas.android.com/tools"
                    tools:keep="$body" />
                """.trimIndent(),
            )
            file.deleteOnExit()
        }
}

class ResourceKeepStaticBaselineTest {

    @Test
    fun artifactVerifyRequired_containsFirebaseCoreFields() {
        val qualifiers = ResourceKeepStaticBaseline.artifactVerifyRequired.map { it.toQualifier() }.toSet()
        assertTrue("string/google_app_id" in qualifiers)
        assertTrue("string/project_id" in qualifiers)
    }

    @Test
    fun entries_includeAdSdkPrefixesViaExactNames() {
        assertTrue(
            ResourceKeepStaticBaseline.entries.any {
                it.type == "drawable" && it.name == "alternative_network"
            },
        )
    }
}
