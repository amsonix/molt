package io.github.amsonix.molt.internal.util

import com.google.firebase.crashlytics.buildtools.gradle.tasks.UploadMappingFileTask
import io.github.amsonix.molt.MoltObfuscateMergeMappingTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashlyticsMappingUploadWiringTest {

    @Test
    fun wire_doesNotConfigureUploadTaskBeforeItIsRealized() {
        val project = ProjectBuilder.builder().build()
        val mergeTask = project.tasks.register(
            "moltObfuscateMergeMappingGoogleRelease",
            MoltObfuscateMergeMappingTask::class.java,
        ) {
            outputMapping.set(project.layout.buildDirectory.file("mapping/shell-obfuscate-mapping.txt"))
        }

        CrashlyticsMappingUploadWiring.wire(
            project = project,
            hookEnabled = true,
            failOnHookFailure = false,
            uploadTaskName = "uploadCrashlyticsMappingFileGoogleRelease",
            mergeTask = mergeTask,
        )

        val upload = project.tasks.register(
            "uploadCrashlyticsMappingFileGoogleRelease",
            UploadMappingFileTask::class.java,
        )
        assertTrue(upload.get().taskDependencies.getDependencies(upload.get()).contains(mergeTask.get()))
        assertTrue(upload.get().mergedMappingFile.isPresent)
    }
}
