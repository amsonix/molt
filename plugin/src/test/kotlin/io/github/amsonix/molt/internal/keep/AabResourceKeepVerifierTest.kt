package io.github.amsonix.molt.internal.keep

import com.android.aapt.Resources
import io.github.amsonix.molt.ResourceKeepResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AabResourceKeepVerifierTest {

    @Test
    fun verify_readsBaseResourceTableAndReportsPresentEntries() {
        withTempDirectory { directory ->
            val aab = writeAab(directory, resourceTable("project_id", "google_app_id"))
            val required = listOf(
                ResourceKeepResource("string", "project_id"),
                ResourceKeepResource("string", "google_app_id"),
            )

            val result = AabResourceKeepVerifier.verify(aab, required)

            assertTrue(result.success)
            assertEquals(required, result.present)
            assertTrue(result.missing.isEmpty())
        }
    }

    @Test
    fun verify_reportsMissingEntriesWithSharedQualifierValidation() {
        withTempDirectory { directory ->
            val aab = writeAab(directory, resourceTable("project_id"))
            val present = ResourceKeepResource("string", "project_id")
            val missing = ResourceKeepResource("string", "google_app_id")

            val result = AabResourceKeepVerifier.verify(aab, listOf(present, missing))

            assertFalse(result.success)
            assertEquals(listOf(present), result.present)
            assertEquals(listOf(missing), result.missing)
        }
    }

    private fun resourceTable(vararg names: String): Resources.ResourceTable =
        Resources.ResourceTable.newBuilder()
            .addPackage(
                Resources.Package.newBuilder()
                    .setPackageName("com.example")
                    .addType(
                        Resources.Type.newBuilder()
                            .setName("string")
                            .addAllEntry(names.map { Resources.Entry.newBuilder().setName(it).build() }),
                    ),
            )
            .build()

    private fun writeAab(directory: File, table: Resources.ResourceTable): File =
        File(directory, "fixture.aab").also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("base/resources.pb"))
                table.writeTo(output)
                output.closeEntry()
            }
        }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("aab-keep-verifier-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
