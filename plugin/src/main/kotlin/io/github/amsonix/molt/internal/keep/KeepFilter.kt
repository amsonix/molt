package io.github.amsonix.molt.internal.keep

internal object KeepFilter {

    fun shouldObfuscate(type: String, name: String, keepRules: List<KeepXmlParser.KeepResource>): Boolean =
        keepRules.none { rule -> matches(rule.type, rule.name, type, name) }

    private fun matches(ruleType: String, ruleName: String, type: String, name: String): Boolean {
        if (!ruleType.equals(type, ignoreCase = true)) return false
        if (ruleName == name) return true
        if (ruleName.endsWith("*")) {
            val prefix = ruleName.dropLast(1)
            return name.startsWith(prefix)
        }
        if (ruleName.startsWith("_") && ruleName.endsWith("*")) {
            return name.startsWith("_")
        }
        return false
    }
}
