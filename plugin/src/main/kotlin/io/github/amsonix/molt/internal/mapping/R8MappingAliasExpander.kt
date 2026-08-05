package io.github.amsonix.molt.internal.mapping

import io.github.amsonix.molt.internal.rename.RenameMapping
import java.io.File

/**
 * post-R8 改包时 DEX 里已是 R8 混淆名（如 view.web.a），需把 R8 别名链接到 shell 目标名。
 */
internal object R8MappingAliasExpander {

    private val CLASS_MAPPING = Regex("""^([\w.$]+?) -> ([\w.$]+?):""")

    fun expand(shellMapping: RenameMapping?, r8MappingFile: File?): RenameMapping? {
        if (shellMapping == null || shellMapping.entries().isEmpty()) return shellMapping
        if (r8MappingFile == null || !r8MappingFile.isFile) return shellMapping

        val r8Forward = parseClassMappings(r8MappingFile)
        if (r8Forward.isEmpty()) return shellMapping

        val extra = linkedMapOf<String, String>()
        for ((original, r8Name) in r8Forward) {
            val shellTarget = shellMapping.resolve(original) ?: continue
            if (r8Name != shellTarget) {
                extra.putIfAbsent(r8Name, shellTarget)
            }
        }
        return if (extra.isEmpty()) shellMapping else shellMapping.mergedWith(RenameMapping.fromForward(extra))
    }

    fun parseClassMappings(r8MappingFile: File): Map<String, String> {
        val forward = linkedMapOf<String, String>()
        r8MappingFile.forEachLine { line ->
            val match = CLASS_MAPPING.matchEntire(line.trim()) ?: return@forEachLine
            forward[match.groupValues[1]] = match.groupValues[2]
        }
        return forward
    }
}
