package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.RenameMapping

/** 替换 plain-text layout/navigation XML 中的 View / Activity FQCN。 */
internal object TextXmlViewClassReplacer {

    fun rewrite(
        input: ByteArray,
        mapping: RenameMapping,
    ): ProtoXmlViewClassReplacer.RewriteResult {
        if (!looksLikeTextXml(input)) {
            return result(
                input,
                ProtoXmlViewClassReplacer.FormatStatus.UNSUPPORTED,
                reason = "not text XML",
            )
        }
        val text = input.toString(Charsets.UTF_8)
        var output = text
        var replacementCount = 0
        mapping.entries()
            .sortedByDescending { entry -> entry.original.length }
            .forEach { entry ->
                val original = entry.original
                val obfuscated = entry.obfuscated
                output = replaceRuntimeName(output, original, obfuscated)?.let { updated ->
                    replacementCount++
                    updated
                } ?: output
                val simple = original.substringAfterLast('.', missingDelimiterValue = original)
                if (simple != original) {
                    val relative = ".$simple"
                    val obfuscatedSimple = obfuscated.substringAfterLast('.', missingDelimiterValue = obfuscated)
                    output = replaceRuntimeName(output, relative, ".$obfuscatedSimple")?.let { updated ->
                        replacementCount++
                        updated
                    } ?: output
                }
            }
        if (replacementCount == 0) {
            return result(input, ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED)
        }
        return result(
            output.toByteArray(Charsets.UTF_8),
            ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED,
            replacementCount = replacementCount,
        )
    }

    private fun replaceRuntimeName(text: String, original: String, replacement: String): String? {
        if (original.isEmpty()) return null
        val regex = Regex("(?<![A-Za-z0-9_.\$])${Regex.escape(original)}(?![A-Za-z0-9_.\$])")
        if (!regex.containsMatchIn(text)) return null
        return regex.replace(text, replacement)
    }

    private fun looksLikeTextXml(input: ByteArray): Boolean {
        val limit = minOf(input.size, 64)
        for (index in 0 until limit) {
            val value = input[index].toInt() and 0xFF
            if (!value.toChar().isWhitespace()) {
                return value.toChar() == '<'
            }
        }
        return false
    }

    private fun result(
        bytes: ByteArray,
        status: ProtoXmlViewClassReplacer.FormatStatus,
        replacementCount: Int = 0,
        reason: String? = null,
    ): ProtoXmlViewClassReplacer.RewriteResult =
        ProtoXmlViewClassReplacer.RewriteResult(bytes, status, replacementCount, reason)
}
