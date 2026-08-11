package io.github.amsonix.molt.internal.util

import org.gradle.api.Project
import java.io.File

/**
 * 跨 AGP 8.0–9.x 读取 source set 目录。
 *
 * 新 DSL [com.android.build.api.dsl.AndroidSourceDirectorySet] 在 AGP 8.0–8.4 没有读取
 * 访问器（只有 srcDir/srcDirs 配置方法），`getDirectories()` 自 AGP 8.5 才引入；
 * AGP 9 移除了旧 DSL 的 `getSrcDirs()`。因此按运行时可用方法优先取新接口，回退旧接口。
 *
 * 注意 `getDirectories()` 返回的是**模块相对路径**（如 `src/main/res`），须相对
 * [Project.getProjectDir] 解析；旧接口 `getSrcDirs()` 直接返回绝对路径。
 */
internal object SourceSetDirectoriesCompat {

    /** 对 AndroidSourceSet 实例取成员（getRes / getJava）对应的目录集合（绝对路径）。 */
    fun of(project: Project, sourceSet: Any, memberName: String): List<File> {
        val directorySet = runCatching {
            sourceSet.javaClass.getMethod(memberName).invoke(sourceSet)
        }.getOrNull() ?: return emptyList()

        // 新 DSL（AGP 8.5+ / 9.x）：getDirectories() -> Set<String>（模块相对路径）
        runCatching {
            val directories = directorySet.javaClass
                .getMethod("getDirectories")
                .invoke(directorySet) as? Set<*>
                ?: error("unexpected getDirectories() return")
            return directories.mapNotNull { value ->
                value?.toString()?.takeIf { it.isNotBlank() }?.let { path ->
                    File(path).let { file ->
                        if (file.isAbsolute) file else File(project.projectDir, path)
                    }
                }
            }
        }.getOrNull() ?: run {
            // 旧 DSL（AGP 8.0–8.4）：getSrcDirs() -> FileCollection（绝对路径）
            runCatching {
                val srcDirs = directorySet.javaClass
                    .getMethod("getSrcDirs")
                    .invoke(directorySet) as? org.gradle.api.file.FileCollection
                    ?: error("unexpected getSrcDirs() return")
                return srcDirs.files.toList()
            }.getOrNull()
        }
        return emptyList()
    }
}
