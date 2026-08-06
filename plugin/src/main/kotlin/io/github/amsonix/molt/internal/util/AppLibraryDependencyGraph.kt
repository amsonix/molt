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
        collectFromProjectDependencies(appProject, result)
        return result
    }

    /** variant classpath 配置可能尚未创建；回退扫描 implementation/api 上的 ProjectDependency。 */
    private fun collectFromProjectDependencies(
        appProject: Project,
        result: MutableSet<Project>,
    ) {
        val pending = ArrayDeque<Project>()
        pending.add(appProject)
        while (pending.isNotEmpty()) {
            val project = pending.removeFirst()
            projectDependencyConfigurationNames.forEach { configName ->
                val config = project.configurations.findByName(configName) ?: return@forEach
                config.allDependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                    val dependencyPath = ProjectDependencyCompat.pathOf(dependency)
                    appProject.evaluationDependsOn(dependencyPath)
                    val dependencyProject = project.project(dependencyPath)
                    if (dependencyProject.plugins.hasPlugin("com.android.library") && result.add(dependencyProject)) {
                        pending.add(dependencyProject)
                    }
                }
            }
        }
    }

    private val projectDependencyConfigurationNames =
        listOf("implementation", "api", "compileOnly", "runtimeOnly")

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
            val dependencyPath = ProjectDependencyCompat.pathOf(dependency)
            root.evaluationDependsOn(dependencyPath)
            visitProject(root, root.project(dependencyPath), appVariantName, result)
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
                val dependencyPath = ProjectDependencyCompat.pathOf(dependency)
                appProject.evaluationDependsOn(dependencyPath)
                visitProject(appProject, project.project(dependencyPath), appVariantName, result)
            }
        }
    }
}
