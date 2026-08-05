package io.github.amsonix.molt.internal.rename

import java.io.File

/** 扫描 layout* 中出现的自定义 View 全限定名，供 R8 后 DEX + 二进制 layout 联动重命名。 */
internal object ViewClassScanner {

    private val XML_COMMENT = Regex("""<!--[\s\S]*?-->""")

    /** 匹配 <com.foo.Bar 或 </com.foo.Bar 形式的自定义 View 标签。 */
    private val CUSTOM_VIEW_TAG = Regex("""<(?:/)?([a-z][\w.]*\.[\w.$]+)\b""")

    /** 匹配 class="com.foo.Bar" 或 type="com.foo.Bar"（DataBinding variable/import）。 */
    private val FQCN_ATTRIBUTE = Regex("""\b(?:class|type)\s*=\s*(["'])([a-z][\w.]*\.[\w.$]+)\1""")

    fun scanLayoutDirs(layoutDirs: Iterable<File>): Set<String> {
        val result = linkedSetOf<String>()
        RuntimeXmlResourceFiles.scan(layoutDirs, includeNavigation = false).forEach { file ->
            result += scanLayoutFile(file)
        }
        return result.filter { isViewRenameCandidate(it) }.toSet()
    }

    fun scanLayoutFile(file: File): Set<String> {
        val text = file.readText().replace(XML_COMMENT, "")
        val fromTags = CUSTOM_VIEW_TAG.findAll(text).map { it.groupValues[1] }
        val fromClassAttr = FQCN_ATTRIBUTE.findAll(text).map { it.groupValues[2] }
        return (fromTags + fromClassAttr).toSet()
    }

    private fun isViewRenameCandidate(fqcn: String): Boolean {
        if (!fqcn.contains('.') || fqcn.startsWith("android.") || fqcn.startsWith("androidx.")) {
            return false
        }
        val simpleName = fqcn.substringAfterLast('.')
        return simpleName.first().isUpperCase()
    }
}

/** 从 res 根目录或任一 layout/navigation 目录发现运行时 XML，兼容仅传入 res/layout 的旧调用方。 */
internal object RuntimeXmlResourceFiles {

    fun scan(
        roots: Iterable<File>,
        includeNavigation: Boolean,
    ): Sequence<File> {
        val directories = linkedSetOf<File>()
        roots.forEach { root ->
            val normalized = root.absoluteFile.normalize()
            val resourceRoot = if (isRuntimeXmlDirectoryName(normalized.name)) {
                normalized.parentFile
            } else {
                normalized
            }
            resourceRoot?.listFiles()
                ?.filterTo(directories) { child ->
                    child.isDirectory &&
                        (isLayoutDirectoryName(child.name) ||
                            includeNavigation && isNavigationDirectoryName(child.name))
                }
        }
        return directories.asSequence()
            .flatMap { directory -> directory.walkTopDown() }
            .filter { file -> file.isFile && file.extension.equals("xml", ignoreCase = true) }
            .distinctBy { file -> file.absoluteFile.normalize() }
    }

    private fun isRuntimeXmlDirectoryName(name: String): Boolean =
        isLayoutDirectoryName(name) || isNavigationDirectoryName(name)

    private fun isLayoutDirectoryName(name: String): Boolean =
        name == "layout" || name.startsWith("layout-")

    private fun isNavigationDirectoryName(name: String): Boolean =
        name == "navigation" || name.startsWith("navigation-")
}
