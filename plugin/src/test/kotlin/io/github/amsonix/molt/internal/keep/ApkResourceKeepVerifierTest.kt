package io.github.amsonix.molt.internal.keep

import com.android.aapt.Resources
import io.github.amsonix.molt.ResourceKeepResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkResourceKeepVerifierTest {

    @Test
    fun verify_usesSharedQualifierValidationForConvertedResourceTable() {
        withTempDirectory { directory ->
            val protoApk = writeProtoApk(directory, resourceTable("project_id"))
            val executable = createFakeAapt2(directory, protoApk)
            val binaryApk = File(directory, "input.apk").apply { writeText("binary fixture") }
            val present = ResourceKeepResource("string", "project_id")
            val missing = ResourceKeepResource("string", "google_app_id")

            val result = ApkResourceKeepVerifier.verify(
                apkFile = binaryApk,
                aapt2Executable = executable,
                required = listOf(present, missing),
            )

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

    private fun writeProtoApk(directory: File, table: Resources.ResourceTable): File =
        File(directory, "proto-fixture.apk").also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("resources.pb"))
                table.writeTo(output)
                output.closeEntry()
            }
        }

    private fun createFakeAapt2(directory: File, protoApk: File): File =
        File(directory, "fake-aapt2").apply {
            writeText(
                """
                |#!/bin/sh
                |output=""
                |while [ "${'$'}#" -gt 0 ]; do
                |  if [ "${'$'}1" = "-o" ]; then
                |    output="${'$'}2"
                |    shift 2
                |  else
                |    shift
                |  fi
                |done
                |cp "${protoApk.absolutePath}" "${'$'}output"
                """.trimMargin(),
            )
            check(setExecutable(true))
        }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("apk-keep-verifier-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
