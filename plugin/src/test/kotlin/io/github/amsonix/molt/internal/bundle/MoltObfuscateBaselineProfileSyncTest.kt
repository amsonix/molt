package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class MoltObfuscateBaselineProfileSyncTest {

    @Test
    fun maybeSync_skipsWhenPostR8DidNotRun() {
        val messages = captureJdkLogs {
            MoltObfuscateBaselineProfileSync.maybeSync(
                logger = it,
                zipFile = File("app.apk"),
                syncEnabled = true,
                postR8Ran = false,
                baselineProf = null,
                obfuscationMapping = null,
                failOnSyncFailure = true,
            )
        }
        assertTrue(messages.any { message -> message.contains("requires componentRename or viewRename") })
    }

    @Test
    fun maybeSync_skipsWhenBaselineMissingEvenIfFailOnEnabled() {
        val messages = captureJdkLogs {
            MoltObfuscateBaselineProfileSync.maybeSync(
                logger = it,
                zipFile = File("app.apk"),
                syncEnabled = true,
                postR8Ran = true,
                baselineProf = null,
                obfuscationMapping = null,
                failOnSyncFailure = true,
            )
        }
        assertEquals(1, messages.size)
        assertTrue(messages.first().contains("baseline-prof.txt missing"))
    }

    @Test
    fun maybeSync_logsSkipWhenFailOnDisabled() {
        val messages = captureJdkLogs {
            MoltObfuscateBaselineProfileSync.maybeSync(
                logger = it,
                zipFile = File("app.apk"),
                syncEnabled = true,
                postR8Ran = true,
                baselineProf = null,
                obfuscationMapping = null,
                failOnSyncFailure = false,
            )
        }
        assertEquals(1, messages.size)
        assertTrue(messages.first().contains("baseline-prof.txt missing"))
    }

    private fun captureJdkLogs(block: (Logger) -> Unit): List<String> {
        val messages = mutableListOf<String>()
        val logger = Logger.getLogger("shell-obfuscate-test-${System.nanoTime()}")
        logger.level = Level.INFO
        logger.useParentHandlers = false
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                messages += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            block(logger)
        } finally {
            logger.removeHandler(handler)
        }
        return messages
    }
}
