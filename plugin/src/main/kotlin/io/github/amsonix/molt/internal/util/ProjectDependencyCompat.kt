package io.github.amsonix.molt.internal.util

import org.gradle.api.artifacts.ProjectDependency

/**
 * Project path for a [ProjectDependency].
 *
 * Use [ProjectDependency.getDependencyProject] instead of [ProjectDependency.getPath]:
 * `getPath()` exists only from Gradle 8.11+, but molt supports Gradle 8.9+ (AGP matrix).
 */
internal object ProjectDependencyCompat {
    @Suppress("DEPRECATION")
    fun pathOf(dependency: ProjectDependency): String = dependency.dependencyProject.path
}
