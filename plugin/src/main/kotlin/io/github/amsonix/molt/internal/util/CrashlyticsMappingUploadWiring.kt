package io.github.amsonix.molt.internal.util

import io.github.amsonix.molt.MoltObfuscateMergeMappingTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/** Crashlytics mapping upload hook; no compile-time dependency on Firebase Gradle plugin. */
internal object CrashlyticsMappingUploadWiring {

    fun wire(
        project: Project,
        hookEnabled: Boolean,
        uploadTaskName: String,
        mergeTask: TaskProvider<MoltObfuscateMergeMappingTask>,
    ) {
        if (!hookEnabled) return
        project.tasks.configureEach {
            if (name != uploadTaskName) return@configureEach
            if (!hasMergedMappingFileProperty(this)) return@configureEach
            dependsOn(mergeTask)
            wireMergedMappingFile(
                project = project,
                taskName = name,
                taskInstance = this,
                mappingFile = mergeTask.flatMap { merged -> merged.outputMapping },
            )
        }
    }

    private fun hasMergedMappingFileProperty(taskInstance: Any): Boolean =
        taskInstance.javaClass.methods.any { it.name == "getMergedMappingFile" && it.parameterCount == 0 }

    private fun wireMergedMappingFile(
        project: Project,
        taskName: String,
        taskInstance: Any,
        mappingFile: Provider<RegularFile>,
    ) {
        try {
            val property = taskInstance.javaClass.getMethod("getMergedMappingFile").invoke(taskInstance)
                ?: run {
                    project.logger.warn("molt: $taskName has no mergedMappingFile property; skip Crashlytics hook")
                    return
                }
            val setMethod = property.javaClass.methods.firstOrNull { method ->
                method.name == "set" &&
                    method.parameterCount == 1 &&
                    Provider::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: property.javaClass.interfaces.flatMap { it.methods.toList() }.firstOrNull { method ->
                method.name == "set" &&
                    method.parameterCount == 1 &&
                    Provider::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: run {
                project.logger.warn("molt: cannot set mergedMappingFile on $taskName; skip Crashlytics hook")
                return
            }
            setMethod.invoke(property, mappingFile)
        } catch (ex: ReflectiveOperationException) {
            project.logger.warn(
                "molt: failed to wire Crashlytics mapping upload for $taskName (${ex.message})",
            )
        }
    }
}
