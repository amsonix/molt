package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class Aapt2ApkConverterTest {

    @Test
    fun convert_passesExpectedArgumentsAndValidatesOutput() {
        withTempDirectory { directory ->
            val executable = createScript(
                directory,
                """
                printf '%s\n' "${'$'}@" > "${'$'}(dirname "${'$'}0")/args.txt"
                output=""
                input=""
                while [ "${'$'}#" -gt 0 ]; do
                  case "${'$'}1" in
                    convert) shift ;;
                    -o) output="${'$'}2"; shift 2 ;;
                    --output-format) format="${'$'}2"; shift 2 ;;
                    *) input="${'$'}1"; shift ;;
                  esac
                done
                printf '%s:' "${'$'}format" > "${'$'}output"
                cat "${'$'}input" >> "${'$'}output"
                """,
            )
            val input = File(directory, "input.apk").apply { writeText("payload") }
            val output = File(directory, "output.apk")

            Aapt2ApkConverter.convert(
                executable = executable,
                input = input,
                output = output,
                format = Aapt2ApkConverter.Format.PROTO,
            )

            assertEquals("proto:payload", output.readText())
            assertEquals(
                listOf(
                    "convert",
                    "-o",
                    output.absolutePath,
                    "--output-format",
                    "proto",
                    input.absolutePath,
                ),
                File(directory, "args.txt").readLines(),
            )
        }
    }

    @Test
    fun convert_nonZeroExitIncludesCommandOutputAndRemovesPartialFile() {
        withTempDirectory { directory ->
            val executable = createScript(
                directory,
                """
                echo "partial" > "${'$'}3"
                echo "converter exploded" >&2
                exit 7
                """,
            )
            val input = File(directory, "input.apk").apply { writeText("payload") }
            val output = File(directory, "output.apk")

            val error = try {
                Aapt2ApkConverter.convert(
                    executable = executable,
                    input = input,
                    output = output,
                    format = Aapt2ApkConverter.Format.BINARY,
                )
                throw AssertionError("expected IOException")
            } catch (e: IOException) {
                e
            }

            assertTrue(error.message.orEmpty().contains("exit=7"))
            assertTrue(error.message.orEmpty().contains("converter exploded"))
            assertFalse(output.exists())
        }
    }

    private fun createScript(directory: File, body: String): File =
        File(directory, "fake-aapt2").apply {
            writeText("#!/bin/sh\n${body.trimIndent()}\n")
            check(setExecutable(true)) { "failed to mark test script executable" }
        }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("aapt2-converter-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
