package io.github.amsonix.molt.internal.util

/** 通用马甲包默认值：从 applicationId 推导，避免写死宿主工程包名。 */
internal object MoltObfuscateDefaults {

    fun junkPackagePrefix(applicationId: String): String =
        "${applicationId.trim()}.shell.junk"

    fun projectPackagePrefixes(applicationId: String): List<String> =
        listOf(normalizePackagePrefix(applicationId))

    fun normalizePackagePrefix(raw: String): String {
        val trimmed = raw.trim().trimEnd('.')
        require(trimmed.isNotEmpty()) { "applicationId must not be blank" }
        return "$trimmed."
    }

    fun shrinkKeepRelativePath(variantName: String, pattern: String): String =
        pattern.replace("{variant}", variantName)
}
