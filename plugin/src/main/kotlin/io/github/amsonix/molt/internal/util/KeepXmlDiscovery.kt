package io.github.amsonix.molt.internal.util

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
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

    fun discoverInAndroidProject(android: BaseExtension?): List<File> {
        if (android == null) return emptyList()
        return keepXmlFilesInResDirs(android.sourceSets.flatMap { it.res.srcDirs })
    }

    fun discoverInProject(project: Project): List<File> =
        discoverInAndroidProject(project.extensions.findByType(BaseExtension::class.java))

    fun discoverForVariant(appProject: Project, variantName: String): List<File> {
        val result = linkedSetOf<File>()
        val appAndroid = appProject.extensions.findByType(AppExtension::class.java)
        discoverInAndroidProject(appAndroid).forEach { result.add(it) }
        AppLibraryDependencyGraph.resolveLibraryProjects(appProject, listOf(variantName))
            .forEach { library -> discoverInProject(library).forEach { result.add(it) } }
        return result.toList()
    }
}
