package io.github.amsonix.molt.internal.rename

import java.io.File

/** 收集源码中必须保留原类名的候选；最终范围由调用方与项目声明类取交集。 */
internal object ClassRenameProtectionScanner {

    private val PACKAGE_REGEX = Regex(
        """\bpackage\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""",
    )
    private val FQCN_REGEX = Regex(
        """[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""",
    )
    private val KEEP_CLASS_REGEX = Regex(
        """@(?:[A-Za-z_]\w*\.)*Keep\b(?:\s*\([^)]*\))?""" +
            """(?:\s+|@(?:[A-Za-z_]\w*\.)*[A-Za-z_]\w*(?:\s*\([^)]*\))?|""" +
            """\b(?:public|protected|private|internal|open|final|abstract|sealed|data|value|""" +
            """inline|enum|annotation|fun|expect|actual|external|strictfp|static|non-sealed)\b)*""" +
            """(?:class|object|interface|record|enum|@interface)\s+([A-Za-z_]\w*)""",
    )

    fun scan(sourceRoots: Iterable<File>): Set<String> {
        val result = linkedSetOf<String>()
        val seenFiles = linkedSetOf<File>()
        sourceRoots.forEach { root ->
            val files = when {
                root.isDirectory -> root.walkTopDown()
                root.isFile -> sequenceOf(root)
                else -> emptySequence()
            }
            files.filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    if (!seenFiles.add(file.absoluteFile.normalize())) return@forEach
                    result += scanSource(file.readText(), isKotlin = file.extension == "kt")
                }
        }
        return result
    }

    private fun scanSource(source: String, isKotlin: Boolean): Set<String> {
        val lexed = lex(source, isKotlin)
        val result = lexed.stringLiterals
            .filterTo(linkedSetOf()) { FQCN_REGEX.matches(it) }
        val packageName = PACKAGE_REGEX.find(lexed.code)?.groupValues?.get(1)
        val depthAt = braceDepthAt(lexed.code)
        KEEP_CLASS_REGEX.findAll(lexed.code).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (depthAt[match.range.first] != 0 || depthAt[nameGroup.range.first] != 0) return@forEach
            result += packageName?.let { "$it.${nameGroup.value}" } ?: nameGroup.value
        }
        return result
    }

    private fun lex(source: String, isKotlin: Boolean): LexedSource {
        val masked = source.toCharArray()
        val literals = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                    mask(masked, index, end)
                    index = end
                }

                source.startsWith("/*", index) -> {
                    val end = blockCommentEnd(source, index)
                    mask(masked, index, end)
                    index = end
                }

                source.startsWith("\"\"\"", index) -> {
                    val closing = source.indexOf("\"\"\"", index + 3)
                    val contentEnd = if (closing < 0) source.length else closing
                    val end = if (closing < 0) source.length else closing + 3
                    val value = source.substring(index + 3, contentEnd)
                    if (!isKotlin || !containsKotlinTemplate(value)) literals += value
                    mask(masked, index, end)
                    index = end
                }

                source[index] == '"' -> {
                    val literal = readQuotedString(source, index, isKotlin)
                    if (literal.isTerminated && !literal.hasTemplate) literals += literal.value
                    mask(masked, index, literal.end)
                    index = literal.end
                }

                source[index] == '\'' -> {
                    val end = quotedEnd(source, index, '\'')
                    mask(masked, index, end)
                    index = end
                }

                else -> index++
            }
        }
        return LexedSource(String(masked), literals)
    }

    private fun blockCommentEnd(source: String, start: Int): Int {
        var depth = 1
        var index = start + 2
        while (index < source.length && depth > 0) {
            when {
                source.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }

                source.startsWith("*/", index) -> {
                    depth--
                    index += 2
                }

                else -> index++
            }
        }
        return index
    }

    private fun readQuotedString(source: String, start: Int, isKotlin: Boolean): QuotedString {
        val value = StringBuilder()
        var index = start + 1
        var hasTemplate = false
        while (index < source.length && source[index] != '\n' && source[index] != '\r') {
            val char = source[index]
            if (char == '"') {
                return QuotedString(value.toString(), index + 1, hasTemplate, isTerminated = true)
            }
            if (char == '\\' && index + 1 < source.length) {
                val escape = decodeEscape(source, index + 1)
                value.append(escape.value)
                index += escape.consumed + 1
                continue
            }
            if (isKotlin && char == '$' && startsKotlinTemplate(source, index)) hasTemplate = true
            value.append(char)
            index++
        }
        return QuotedString(value.toString(), index, hasTemplate, isTerminated = false)
    }

    private fun decodeEscape(source: String, escapedIndex: Int): DecodedEscape {
        val escaped = source[escapedIndex]
        if (escaped == 'u' && escapedIndex + 4 < source.length) {
            val hex = source.substring(escapedIndex + 1, escapedIndex + 5)
            hex.toIntOrNull(16)?.let { return DecodedEscape(it.toChar().toString(), 5) }
        }
        val value = when (escaped) {
            'b' -> "\b"
            't' -> "\t"
            'n' -> "\n"
            'f' -> "\u000C"
            'r' -> "\r"
            else -> escaped.toString()
        }
        return DecodedEscape(value, 1)
    }

    private fun quotedEnd(source: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < source.length) {
            when {
                source[index] == '\\' -> index += 2
                source[index] == quote -> return index + 1
                source[index] == '\n' || source[index] == '\r' -> return index
                else -> index++
            }
        }
        return source.length
    }

    private fun containsKotlinTemplate(value: String): Boolean =
        value.indices.any { index -> value[index] == '$' && startsKotlinTemplate(value, index) }

    private fun startsKotlinTemplate(value: String, dollarIndex: Int): Boolean {
        val next = value.getOrNull(dollarIndex + 1) ?: return false
        return next == '{' || Character.isJavaIdentifierStart(next)
    }

    private fun braceDepthAt(source: String): IntArray {
        val result = IntArray(source.length)
        var depth = 0
        source.forEachIndexed { index, char ->
            result[index] = depth
            when (char) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
        }
        return result
    }

    private fun mask(chars: CharArray, start: Int, end: Int) {
        for (index in start until end) {
            if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
        }
    }

    private data class LexedSource(
        val code: String,
        val stringLiterals: List<String>,
    )

    private data class QuotedString(
        val value: String,
        val end: Int,
        val hasTemplate: Boolean,
        val isTerminated: Boolean,
    )

    private data class DecodedEscape(
        val value: String,
        val consumed: Int,
    )
}
