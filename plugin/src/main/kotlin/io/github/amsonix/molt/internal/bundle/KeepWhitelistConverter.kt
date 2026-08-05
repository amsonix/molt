package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.keep.KeepXmlParser

/**
 * 将 keep.xml 的 tools:keep 条目转为 ResChiper whiteList 规则。
 *
 * layout/foo → *.R.layout.foo
 * layout/mbridge_* → *.R.layout.mbridge_*
 */
internal object KeepWhitelistConverter {

    fun fromKeepResources(keepRules: List<KeepXmlParser.KeepResource>): Set<String> {
        val rules = linkedSetOf<String>()
        // raw/keep.xml 自身及常见动态加载目录
        rules += "res/raw/*"
        keepRules.forEach { rule ->
            rules += toResChiperRule(rule)
        }
        return rules
    }

    private fun toResChiperRule(rule: KeepXmlParser.KeepResource): String {
        val type = rule.type.lowercase()
        val name = rule.name
        return "*.$R_TYPE.$type.$name"
    }

    private const val R_TYPE = "R"
}
