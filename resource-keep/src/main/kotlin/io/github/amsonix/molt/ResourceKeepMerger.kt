package io.github.amsonix.molt

object ResourceKeepMerger {

    /**
     * shell 制品混淆的统一 keep 入口：静态安全基线和内置通配规则始终参与合并，
     * [dynamic] 仅承载 shrink-verify 等可选来源。
     */
    fun mergeShellKeepEntries(
        declared: List<ResourceKeepResource>,
        dynamic: List<ResourceKeepResource> = emptyList(),
    ): List<ResourceKeepResource> = mergeKeepEntries(
        declared = declared,
        detected = dynamic,
        includeBuiltinWildcards = true,
        includeStaticBaseline = true,
    )

    fun mergeKeepEntries(
        declared: List<ResourceKeepResource>,
        detected: List<ResourceKeepResource> = emptyList(),
        includeBuiltinWildcards: Boolean = true,
        includeStaticBaseline: Boolean = false,
    ): List<ResourceKeepResource> {
        val wildcards = if (includeBuiltinWildcards) {
            ResourceKeepBuiltinWildcards.entries
        } else {
            emptyList()
        }
        val baseline = if (includeStaticBaseline) {
            ResourceKeepStaticBaseline.entries
        } else {
            emptyList()
        }
        return compactWithWildcards(
            (declared + detected + baseline + wildcards).distinctBy { it.toQualifier() },
        )
    }

    fun compactWithWildcards(entries: List<ResourceKeepResource>): List<ResourceKeepResource> {
        val wildcards = entries.filter { it.name.endsWith("*") }
        val exactEntries = entries.filter { !it.name.endsWith("*") }
        val uncovered = exactEntries.filter { entry ->
            wildcards.none { wildcard -> wildcard.covers(entry) }
        }
        return (wildcards + uncovered).sortedWith(compareBy({ it.type }, { it.name }))
    }

    fun uncoveredByDeclared(
        detected: List<ResourceKeepResource>,
        declared: List<ResourceKeepResource>,
    ): List<ResourceKeepResource> {
        val declaredWildcards = declared.filter { it.name.endsWith("*") }
        val declaredExact = declared.map { it.toQualifier() }.toSet()
        return detected.filter { entry ->
            entry.toQualifier() !in declaredExact &&
                declaredWildcards.none { wildcard -> wildcard.covers(entry) }
        }
    }

    fun isCovered(entry: ResourceKeepResource, keepRules: List<ResourceKeepResource>): Boolean {
        if (entry.toQualifier() in keepRules.map { it.toQualifier() }.toSet()) return true
        return keepRules.any { rule -> rule.covers(entry) }
    }
}
