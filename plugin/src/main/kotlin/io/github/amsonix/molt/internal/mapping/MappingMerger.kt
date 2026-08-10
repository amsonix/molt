package io.github.amsonix.molt.internal.mapping

import io.github.amsonix.molt.internal.rename.RenameMapping
import java.io.File

internal object MappingMerger {

    private val CLASS_HEADER_REGEX = Regex(
        """(?m)^(([^#\s]\S*)[ \t]+->[ \t]+)([^:\s]+)(:[ \t]*\r?)$""",
    )
    private val METADATA_LINE_REGEX = Regex("""(?m)^[ \t]*#[^\n]*$""")
    private val RESIDUAL_SIGNATURE_ID_REGEX = Regex(
        """"id"\s*:\s*"com\.android\.tools\.r8\.residualsignature"""",
    )
    private val SIGNATURE_PROPERTY_REGEX = Regex("""("signature"\s*:\s*")([^"]*)(")""")
    private val TYPE_DESCRIPTOR_REGEX = Regex("""L([^;]+);""")

    /**
     * 将 post-R8 shell 重命名直接合成为 original -> runtimeName，
     * 成员映射及其他原始内容保持不变。
     */
    fun compose(r8Mapping: String, shellMapping: RenameMapping): String =
        merge(r8Mapping, shellMapping)

    private fun merge(
        originalMapping: String,
        shellMapping: RenameMapping,
    ): String {
        val mappedOriginals = linkedSetOf<String>()
        val residualTypeMapping = linkedMapOf<String, String>()
        val mergedClassHeaders = CLASS_HEADER_REGEX.replace(originalMapping) { match ->
            val original = match.groupValues[2]
            mappedOriginals += original
            val shellTarget = shellMapping.resolve(original)
            if (shellTarget == null) {
                match.value
            } else {
                residualTypeMapping[match.groupValues[3].replace('.', '/')] =
                    shellTarget.replace('.', '/')
                match.groupValues[1] + shellTarget + match.groupValues[4]
            }
        }
        // 追加类（keep 且未被 R8 收录）也要进 residualTypeMapping：
        // 否则 residual signature 中的类型引用与 DEX 实际名不一致（调试元数据错乱）。
        val missingEntries = shellMapping.entries()
            .filterNot { it.original in mappedOriginals }
            .sortedBy { it.original }
        for (entry in missingEntries) {
            residualTypeMapping[entry.original.replace('.', '/')] =
                entry.obfuscated.replace('.', '/')
        }
        val merged = rewriteResidualSignatures(mergedClassHeaders, residualTypeMapping)
        val missingLines = missingEntries.map { "${it.original} -> ${it.obfuscated}:" }
        return appendLines(merged, missingLines)
    }

    private fun rewriteResidualSignatures(
        mapping: String,
        residualTypeMapping: Map<String, String>,
    ): String {
        if (residualTypeMapping.isEmpty()) return mapping
        return METADATA_LINE_REGEX.replace(mapping) { line ->
            if (!RESIDUAL_SIGNATURE_ID_REGEX.containsMatchIn(line.value)) {
                line.value
            } else {
                SIGNATURE_PROPERTY_REGEX.replace(line.value) { property ->
                    val remapped = TYPE_DESCRIPTOR_REGEX.replace(property.groupValues[2]) { type ->
                        residualTypeMapping[type.groupValues[1]]
                            ?.let { replacement -> "L$replacement;" }
                            ?: type.value
                    }
                    property.groupValues[1] + remapped + property.groupValues[3]
                }
            }
        }
    }

    private fun appendLines(mapping: String, lines: List<String>): String {
        if (lines.isEmpty()) return mapping
        val lineSeparator = when {
            mapping.contains("\r\n") -> "\r\n"
            mapping.contains('\r') -> "\r"
            else -> "\n"
        }
        return buildString {
            append(mapping)
            if (mapping.isNotEmpty() && !mapping.endsWith('\n') && !mapping.endsWith('\r')) {
                append(lineSeparator)
            }
            append(lines.joinToString(lineSeparator))
        }
    }

    fun writeComponentMappingReport(reportFile: File, lines: List<String>) {
        reportFile.parentFile.mkdirs()
        reportFile.writeText(buildString {
            appendLine("# shell-obfuscate component mapping")
            lines.forEach { appendLine(it) }
        })
    }

    fun toProguardMappingLines(originalToObfuscated: Map<String, String>): List<String> =
        originalToObfuscated.entries
            .sortedBy { it.key }
            // ProGuard/R8 class mapping 行以冒号结尾
            .map { (original, obfuscated) -> "$original -> $obfuscated:" }
}
