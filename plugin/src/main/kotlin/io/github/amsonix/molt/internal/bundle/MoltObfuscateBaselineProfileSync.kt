package io.github.amsonix.molt.internal.bundle

import org.gradle.api.logging.Logger
import java.io.File
import java.util.logging.Logger as JdkLogger

internal object MoltObfuscateBaselineProfileSync {

    fun maybeSync(
        logger: Logger,
        zipFile: File,
        syncEnabled: Boolean,
        postR8Ran: Boolean,
        baselineProf: File?,
        obfuscationMapping: File?,
        failOnSyncFailure: Boolean,
    ) {
        maybeSyncInternal(
            logLifecycle = logger::lifecycle,
            logInfo = logger::info,
            zipFile = zipFile,
            syncEnabled = syncEnabled,
            postR8Ran = postR8Ran,
            baselineProf = baselineProf,
            obfuscationMapping = obfuscationMapping,
            failOnSyncFailure = failOnSyncFailure,
        )
    }

    fun maybeSync(
        logger: JdkLogger,
        zipFile: File,
        syncEnabled: Boolean,
        postR8Ran: Boolean,
        baselineProf: File?,
        obfuscationMapping: File?,
        failOnSyncFailure: Boolean,
    ) {
        maybeSyncInternal(
            logLifecycle = logger::info,
            logInfo = logger::info,
            zipFile = zipFile,
            syncEnabled = syncEnabled,
            postR8Ran = postR8Ran,
            baselineProf = baselineProf,
            obfuscationMapping = obfuscationMapping,
            failOnSyncFailure = failOnSyncFailure,
        )
    }

    private fun maybeSyncInternal(
        logLifecycle: (String) -> Unit,
        logInfo: (String) -> Unit,
        zipFile: File,
        syncEnabled: Boolean,
        postR8Ran: Boolean,
        baselineProf: File?,
        obfuscationMapping: File?,
        failOnSyncFailure: Boolean,
    ) {
        if (!syncEnabled) return
        if (!postR8Ran) {
            logInfo(
                "molt: baseline profile sync skipped " +
                    "(requires componentRename or viewRename; artifact=${zipFile.name})",
            )
            return
        }
        val humanReadable = baselineProf?.takeIf { it.isFile } ?: run {
            logInfo(
                "molt: baseline profile sync skipped (baseline-prof.txt missing; " +
                    "artifact=${zipFile.name})",
            )
            return
        }
        val result = ArtProfileSync.syncZipInPlace(
            zipFile,
            ArtProfileSync.Config(
                humanReadable,
                obfuscationMapping?.takeIf { it.isFile },
            ),
        )
        if (result.synced) {
            logLifecycle("molt: ${result.message} artifact=${zipFile.name}")
            return
        }
        val message = "molt: baseline profile sync failed (${result.message}; artifact=${zipFile.name})"
        if (failOnSyncFailure) {
            error(message)
        } else {
            logInfo(message)
        }
    }
}
