package io.github.amsonix.molt.internal.keep

import com.android.aapt.Resources
import io.github.amsonix.molt.ResourceKeepResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoltObfuscateArtifactVerifyTest {

    @Test
    fun resolveRequired_mergesFirebaseAndExactKeepEntries() {
        val keepRules = listOf(
            KeepXmlParser.KeepResource("layout", "ad_banner"),
            KeepXmlParser.KeepResource("drawable", "tt_*"),
        )
        val required = MoltObfuscateArtifactVerify.resolveRequired(
            declaredKeepRules = keepRules,
            useFirebaseBaseline = true,
        )
        assertTrue(required.any { it.type == "layout" && it.name == "ad_banner" })
        assertTrue(required.any { it.type == "string" && it.name == "google_app_id" })
        assertTrue(required.none { it.name.endsWith("*") })
    }

    @Test
    fun resolveRequired_keepXmlOnlyWhenFirebaseDisabled() {
        val keepRules = listOf(KeepXmlParser.KeepResource("raw", "keep"))
        val required = MoltObfuscateArtifactVerify.resolveRequired(
            declaredKeepRules = keepRules,
            useFirebaseBaseline = false,
        )
        assertEquals(listOf("raw/keep"), required.map { it.toQualifier() })
    }

    @Test
    fun filterRequiredPresentInTable_skipsResourcesAbsentFromInputArtifact() {
        val table = resourceTable("google_app_id", "project_id")
        val required = listOf(
            ResourceKeepResource("string", "google_app_id"),
            ResourceKeepResource("string", "firebase_database_url"),
            ResourceKeepResource("bool", "config_screen_has_notch"),
        )
        val present = MoltObfuscateArtifactVerify.filterRequiredPresentInTable(table, required)
        assertEquals(listOf("string/google_app_id"), present.map { it.toQualifier() })
    }

    @Test
    fun resolveRequiredPresentInTable_intersectsDeclaredKeepWithInputTable() {
        val table = Resources.ResourceTable.newBuilder()
            .addPackage(
                Resources.Package.newBuilder()
                    .setPackageName("com.example")
                    .addType(
                        Resources.Type.newBuilder()
                            .setName("string")
                            .addEntry(Resources.Entry.newBuilder().setName("google_app_id")),
                    )
                    .addType(
                        Resources.Type.newBuilder()
                            .setName("layout")
                            .addEntry(Resources.Entry.newBuilder().setName("ad_banner")),
                    ),
            )
            .build()
        val declared = listOf(
            KeepXmlParser.KeepResource("layout", "ad_banner"),
            KeepXmlParser.KeepResource("raw", "applovin_settings"),
        )
        val required = MoltObfuscateArtifactVerify.resolveRequiredPresentInTable(
            declaredKeepRules = declared,
            useFirebaseBaseline = true,
            table = table,
        )
        assertEquals(
            setOf("string/google_app_id", "layout/ad_banner"),
            required.map { it.toQualifier() }.toSet(),
        )
    }

    private fun resourceTable(vararg names: String): Resources.ResourceTable {
        val typeBuilder = Resources.Type.newBuilder().setName("string")
        names.forEach { name ->
            typeBuilder.addEntry(Resources.Entry.newBuilder().setName(name))
        }
        return Resources.ResourceTable.newBuilder()
            .addPackage(
                Resources.Package.newBuilder()
                    .setPackageName("com.example")
                    .addType(typeBuilder),
            )
            .build()
    }
}
