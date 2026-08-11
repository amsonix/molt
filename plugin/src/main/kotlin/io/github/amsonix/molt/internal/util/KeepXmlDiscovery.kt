package io.github.amsonix.molt.internal.util

import com.android.build.api.dsl.CommonExtension
import io.github.amsonix.molt.internal.util.SourceSetDirectoriesCompat
import org.gradle.api.Project
import java.io.File

/** 从 app 与依赖图上的 android.library 模块收集 res/raw/keep.xml。 */
internal object KeepXmlDiscovery {

    const val KEEP_XML_RELATIVE_PATH = "raw/keep.xml"

    fun keepXmlFilesInResDirs(resDirs: Iterable<File>): List<File> =
        resDirs
            .map { resDir -> File(resDir, KEEP_XML_RELATIVE_PATH) }
            .filter { it.isFile }
            .distinctBy { it.absoluteFile.normalize() }

    // AGP 8/9 的 app 与 library 扩展都实现新 DSL CommonExtension
    // （AGP 9 移除了旧 com.android.build.gradle.AppExtension / BaseExtension 注册）。
    fun discoverInAndroidProject(
        project: Project,
        android: CommonExtension<*, *, *, *, *, *>?,
    ): List<File> {
        if (android == null) return emptyList()
        return keepXmlFilesInResDirs(
            android.sourceSets.flatMap { SourceSetDirectoriesCompat.of(project, it, "getRes") },
        )
    }

    fun discoverInProject(project: Project): List<File> {
        val ext = project.extensions.findByType(CommonExtension::class.java)
        return discoverInAndroidProject(project, ext)
    }

    fun discoverForVariant(appProject: Project, variantName: String): List<File> {
        val result = linkedSetOf<File>()
        val appAndroid = appProject.extensions.findByType(CommonExtension::class.java)
        discoverInAndroidProject(appProject, appAndroid).forEach { result.add(it) }
        AppLibraryDependencyGraph.resolveLibraryProjects(appProject, listOf(variantName))
            .forEach { library -> discoverInProject(library).forEach { result.add(it) } }
        return result.toList()
    }
}
