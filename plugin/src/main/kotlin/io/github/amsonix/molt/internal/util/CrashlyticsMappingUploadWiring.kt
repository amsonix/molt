package io.github.amsonix.molt.internal.util

import io.github.amsonix.molt.MoltObfuscateMergeMappingTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/** Crashlytics mapping upload hook; no runtime dependency on Firebase Gradle plugin. */
internal object CrashlyticsMappingUploadWiring {

    fun wire(
        project: Project,
        hookEnabled: Boolean,
        failOnHookFailure: Boolean,
        uploadTaskName: String,
        mergeTask: TaskProvider<MoltObfuscateMergeMappingTask>,
    ) {
        if (!hookEnabled) return
        val mappingFile = mergeTask.flatMap { merged -> merged.outputMapping }
        project.tasks.configureEach {
            if (name != uploadTaskName) return@configureEach
            if (!isCrashlyticsUploadMappingFileTask(this)) return@configureEach
            dependsOn(mergeTask)
            val binding = CrashlyticsUploadTaskBinding.resolve(this)
            if (binding == null) {
                reportHookIssue(
                    project = project,
                    failOnHookFailure = failOnHookFailure,
                    message = "molt: $name has no Crashlytics mapping hook API; skip upload wiring",
                )
                return@configureEach
            }
            runCatching {
                binding.applyMapping(
                    task = this,
                    mappingFile = mappingFile,
                    project = project,
                )
            }.onFailure { error ->
                reportHookIssue(
                    project = project,
                    failOnHookFailure = failOnHookFailure,
                    message = "molt: failed to wire Crashlytics mapping upload for $name (${error.message})",
                    cause = error,
                )
            }
        }
    }

    private fun reportHookIssue(
        project: Project,
        failOnHookFailure: Boolean,
        message: String,
        cause: Throwable? = null,
    ) {
        if (failOnHookFailure) {
            throw GradleException(message, cause)
        }
        project.logger.warn(message)
    }
}
