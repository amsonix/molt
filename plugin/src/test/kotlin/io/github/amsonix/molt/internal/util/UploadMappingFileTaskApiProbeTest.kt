package io.github.amsonix.molt.internal.util

import com.google.firebase.crashlytics.buildtools.gradle.tasks.UploadMappingFileTask
import org.junit.Test
import org.junit.Assert.assertTrue

/** 诊断 UploadMappingFileTask 反射 API（本地调试）。 */
class UploadMappingFileTaskApiProbeTest {

    @Test
    fun printUploadMappingFileTaskApi() {
        val methods = UploadMappingFileTask::class.java.methods
            .filter { it.parameterCount == 0 || (it.name.contains("Mapping", ignoreCase = true) && it.parameterCount == 1) }
            .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}) -> ${it.returnType.simpleName}" }
            .sorted()
        println("UploadMappingFileTask API (${UploadMappingFileTask::class.java.name}):")
        methods.forEach { println("  $it") }
        assertTrue(methods.isNotEmpty())
    }
}
