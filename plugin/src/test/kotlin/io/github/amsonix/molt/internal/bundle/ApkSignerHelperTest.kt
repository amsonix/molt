package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ApkSignerHelperTest {

    @Test
    fun sign_passesPasswordsThroughChildProcessEnvironment() {
        withTempDirectory { directory ->
            val executable = File(directory, "fake-apksigner").apply {
                writeText(
                    """
                    |#!/bin/sh
                    |printf '%s\n' "${'$'}@" > "${directory.path}/args.txt"
                    |printf '%s' "${'$'}$STORE_PASSWORD_ENV" > "${directory.path}/store-password.txt"
                    |printf '%s' "${'$'}$KEY_PASSWORD_ENV" > "${directory.path}/key-password.txt"
                    """.trimMargin(),
                )
                check(setExecutable(true))
            }
            val apk = File(directory, "output.apk").apply { writeText("apk") }
            val storeFile = File(directory, "signing.jks").apply { writeText("keystore") }
            val storePassword = "store secret with spaces"
            val keyPassword = "key secret with spaces"

            ApkSignerHelper.sign(
                apk = apk,
                signing = SigningConfigSnapshot(
                    storeFile = storeFile,
                    storePassword = storePassword,
                    keyAlias = "release",
                    keyPassword = keyPassword,
                ),
                apksigner = executable,
            )

            val arguments = File(directory, "args.txt").readLines()
            assertTrue("env:$STORE_PASSWORD_ENV" in arguments)
            assertTrue("env:$KEY_PASSWORD_ENV" in arguments)
            assertFalse(arguments.any { it.contains(storePassword) || it.contains(keyPassword) })
            assertEquals(storePassword, File(directory, "store-password.txt").readText())
            assertEquals(keyPassword, File(directory, "key-password.txt").readText())
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("apk-signer-helper-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val STORE_PASSWORD_ENV = "SHELL_OBFUSCATE_STORE_PASSWORD"
        const val KEY_PASSWORD_ENV = "SHELL_OBFUSCATE_KEY_PASSWORD"
    }
}
