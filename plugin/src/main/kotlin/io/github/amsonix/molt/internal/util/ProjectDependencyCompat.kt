package io.github.amsonix.molt.internal.util

import org.gradle.api.artifacts.ProjectDependency
import java.lang.reflect.Method

/**
 * Project path for a [ProjectDependency].
 *
 * - `getPath()`: 仅 Gradle 8.11+ 存在（molt 支持 8.9+）。
 * - `getDependencyProject()`: 8.x 可用，Gradle 9 已移除（直接调用会 NoSuchMethodError）。
 * 运行时按接口实际暴露的方法选择，保证 8.9–9.x 全兼容。
 */
internal object ProjectDependencyCompat {
    private val pathMethod: Method? = ProjectDependency::class.java.methods
        .firstOrNull { it.name == "getPath" && it.parameterCount == 0 }

    fun pathOf(dependency: ProjectDependency): String {
        pathMethod?.let { method ->
            @Suppress("UNCHECKED_CAST")
            return (method.invoke(dependency) as String)
        }
        @Suppress("DEPRECATION")
        return dependency.dependencyProject.path
    }
}
