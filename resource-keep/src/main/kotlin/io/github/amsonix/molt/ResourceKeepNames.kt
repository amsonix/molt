package io.github.amsonix.molt

object ResourceKeepNames {

    private val RESOURCE_TYPE_NAMES = setOf(
        "anim", "array", "attr", "bool", "color", "dimen", "drawable", "font", "id",
        "integer", "layout", "menu", "mipmap", "plurals", "raw", "string", "style", "xml",
    )

    private val INVALID_LITERAL_NAMES = setOf(
        "#",
        "-",
        ":",
        ",",
        ",,",
        "ComponentUtil",
        "layout",
        "drawable",
        "id",
        "android.resource",
        "adapter_class",
        "bold",
        "gone",
        "in",
    )

    fun isValidScanLiteral(name: String): Boolean = isValidKeepName(name, allowWildcard = false)

    fun isValidKeepName(name: String, allowWildcard: Boolean = true): Boolean {
        if (name.isBlank() || name.contains('/')) return false
        if (name in INVALID_LITERAL_NAMES) return false
        if (name.endsWith("_") && !(allowWildcard && name.endsWith("*"))) return false
        if (name.contains(".") && !name.startsWith("Theme.")) return false
        if (name.contains("*")) {
            if (!allowWildcard || !name.endsWith("*")) return false
            val prefix = name.removeSuffix("*")
            return prefix.isEmpty() || prefix.matches(KEEP_PREFIX_REGEX)
        }
        return name.matches(KEEP_EXACT_REGEX)
    }

    private val KEEP_EXACT_REGEX = Regex("""[A-Za-z0-9_.]+""")
    private val KEEP_PREFIX_REGEX = Regex("""[A-Za-z0-9_.]*""")

    fun isResourceTypeName(value: String): Boolean = value in RESOURCE_TYPE_NAMES
}
