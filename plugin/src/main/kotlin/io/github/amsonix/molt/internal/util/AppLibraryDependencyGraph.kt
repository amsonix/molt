package io.github.amsonix.molt.internal.util

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/** 从 app runtime classpath 解析依赖图上的 android.library 模块。 */
internal object AppLibraryDependencyGraph {

    fun resolveLibraryProjects(appProject: Project, variantNames: Iterable<String>): Set<Project> {
        val result = linkedSetOf<Project>()
        variantNames.forEach { variantName ->
            classpathConfigurationNames(variantName).forEach { configName ->
                collectFromConfiguration(appProject, configName, variantName, result)
            }
        }
        return result
    }

    internal fun classpathConfigurationNames(variantName: String): List<String> =
        listOf("${variantName}RuntimeClasspath", "${variantName}CompileClasspath")

    private fun collectFromConfiguration(
        root: Project,
        configurationName: String,
        appVariantName: String,
        result: MutableSet<Project>,
    ) {
        val config = root.configurations.findByName(configurationName) ?: return
        config.allDependencies.withType(ProjectDependency::class.java).forEach { dependency ->
            root.evaluationDependsOn(dependency.path)
            visitProject(root, root.project(dependency.path), appVariantName, result)
        }
    }

    private fun visitProject(
        appProject: Project,
        project: Project,
        appVariantName: String,
        result: MutableSet<Project>,
    ) {
        if (!project.plugins.hasPlugin("com.android.library")) return
        if (!result.add(project)) return
        project.configurations.filter { configuration ->
            val variantName = configuration.name
                .removeSuffix("RuntimeClasspath")
                .removeSuffix("CompileClasspath")
            configuration.isCanBeResolved &&
                variantName.isNotBlank() &&
                appVariantName.endsWith(variantName, ignoreCase = true)
        }.forEach { configuration ->
            configuration.allDependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                appProject.evaluationDependsOn(dependency.path)
                visitProject(appProject, project.project(dependency.path), appVariantName, result)
            }
        }
    }
}
