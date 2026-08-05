package io.github.amsonix.molt.internal.reschiper.obfuscation

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipOutputStream

class ResourcesObfuscatorLifecycleTest {

    @Test
    fun use_closesInputZipAfterSuccessfulBlock() {
        withObfuscator { obfuscator ->
            obfuscator.use { /* 生命周期由 Closeable.use 管理 */ }

            assertClosed(obfuscator)
        }
    }

    @Test
    fun use_closesInputZipWhenBlockThrows() {
        withObfuscator { obfuscator ->
            try {
                obfuscator.use {
                    throw ExpectedFailure()
                }
                fail("expected test failure")
            } catch (_: ExpectedFailure) {
                // expected
            }

            assertClosed(obfuscator)
        }
    }

    @Test
    fun close_isIdempotentAndImplementsCloseable() {
        withObfuscator { obfuscator ->
            val closeable: Closeable = obfuscator
            closeable.close()
            obfuscator.close()
        }
    }

    private fun withObfuscator(block: (ResourcesObfuscator) -> Unit) {
        val directory = Files.createTempDirectory("resources-obfuscator-lifecycle-test").toFile()
        try {
            val bundle = File(directory, "input.aab")
            ZipOutputStream(bundle.outputStream()).use { }
            val obfuscator = ResourcesObfuscator(
                bundle.toPath(),
                null,
                emptySet(),
                directory.toPath(),
                null,
            )
            block(obfuscator)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertClosed(obfuscator: ResourcesObfuscator) {
        try {
            obfuscator.obfuscate()
            fail("closed obfuscator must reject reuse")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("closed"))
        }
    }

    private class ExpectedFailure : RuntimeException()
}
