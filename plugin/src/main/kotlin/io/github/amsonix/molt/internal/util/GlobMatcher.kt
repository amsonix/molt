package io.github.amsonix.molt.internal.util

/** 简单 glob：`?` 单字符，`*` 任意段（不含 `/`），`**` 任意路径。 */
internal object GlobMatcher {

    fun matches(path: String, pattern: String): Boolean {
        val normalized = normalizePath(path)
        val regex = globToRegex(pattern.trim())
        return regex.matches(normalized) || regex.matches("/$normalized")
    }

    fun anyMatch(path: String, patterns: Collection<String>): Boolean =
        patterns.any { pattern -> pattern.isNotBlank() && matches(path, pattern) }

    private fun globToRegex(glob: String): Regex {
        val builder = StringBuilder("^")
        var index = 0
        while (index < glob.length) {
            when {
                glob.startsWith("**", index) -> {
                    builder.append(".*")
                    index += 2
                }
                glob[index] == '*' -> {
                    builder.append("[^/]*")
                    index++
                }
                glob[index] == '?' -> {
                    builder.append("[^/]")
                    index++
                }
                else -> {
                    builder.append(Regex.escape(glob[index].toString()))
                    index++
                }
            }
        }
        builder.append('$')
        return Regex(builder.toString())
    }
}
