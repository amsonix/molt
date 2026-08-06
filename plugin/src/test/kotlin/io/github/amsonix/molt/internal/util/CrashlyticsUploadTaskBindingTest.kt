package io.github.amsonix.molt.internal.util

import com.google.firebase.crashlytics.buildtools.gradle.tasks.UploadMappingFileTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashlyticsUploadTaskBindingTest {

    @Test
    fun resolve_returnsNullForNonCrashlyticsTask() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("plainTask").get()
        assertNull(CrashlyticsUploadTaskBinding.resolve(task))
    }

    @Test
    fun resolve_bindsMergedMappingFileOnUploadTask() {
        val project = ProjectBuilder.builder().build()
        val upload = project.tasks.register("uploadCrashlyticsMappingFileRelease", UploadMappingFileTask::class.java).get()
        val binding = CrashlyticsUploadTaskBinding.resolve(upload)
        assertNotNull(binding)

        val mappingFile = project.layout.buildDirectory.file("mapping/shell-obfuscate-mapping.txt")
        binding!!.applyMapping(upload, mappingFile, project)

        assertTrue(upload.mergedMappingFile.isPresent)
        assertTrue(upload.mergedMappingFile.get().asFile.path.endsWith("shell-obfuscate-mapping.txt"))
    }

    @Test
    fun resolve_reusesCachedBindingForSameTaskClass() {
        val project = ProjectBuilder.builder().build()
        val first = project.tasks.register("uploadCrashlyticsMappingFileRelease", UploadMappingFileTask::class.java).get()
        val second = project.tasks.register("uploadCrashlyticsMappingFileDebug", UploadMappingFileTask::class.java).get()
        val firstBinding = CrashlyticsUploadTaskBinding.resolve(first)
        val secondBinding = CrashlyticsUploadTaskBinding.resolve(second)
        assertNotNull(firstBinding)
        assertTrue(firstBinding === secondBinding)
    }
}
