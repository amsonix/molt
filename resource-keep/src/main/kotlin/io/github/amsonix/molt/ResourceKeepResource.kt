package io.github.amsonix.molt

data class ResourceKeepResource(
    val type: String,
    val name: String,
) {
    fun toQualifier(): String = "$type/$name"

    fun toKeepToken(): String = "@$type/$name"

    fun covers(other: ResourceKeepResource): Boolean {
        if (type != other.type || !name.endsWith("*")) return false
        val prefix = name.removeSuffix("*")
        return other.name.startsWith(prefix)
    }
}
