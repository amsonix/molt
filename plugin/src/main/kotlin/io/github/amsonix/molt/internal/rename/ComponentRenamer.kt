package io.github.amsonix.molt.internal.rename

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amsonix.molt.internal.util.ObfuscateNaming
import io.github.amsonix.molt.internal.util.SeedRandom
import io.github.amsonix.molt.internal.util.normalizePath
import java.io.File
import java.util.Random

internal data class ComponentRenameEntry(
    val original: String,
    val obfuscated: String,
)

internal class RenameMapping private constructor(
    private val forward: Map<String, String>,
) {
    fun resolve(original: String): String? {
        forward[original]?.let { return it }
        val dollar = original.indexOf('$')
        if (dollar <= 0) return null
        val outer = original.substring(0, dollar)
        val suffix = original.substring(dollar)
        return forward[outer]?.let { obfuscated -> obfuscated + suffix }
    }

    fun entries(): List<ComponentRenameEntry> =
        forward.entries.map { ComponentRenameEntry(it.key, it.value) }

    /** 合并 DEX 补丁 mapping；组件与 View 候选已互斥，冲突时保留先写入项。 */
    fun mergedWith(other: RenameMapping): RenameMapping {
        if (other.forward.isEmpty()) return this
        if (forward.isEmpty()) return other
        val merged = LinkedHashMap(forward)
        other.forward.forEach { (key, value) -> merged.putIfAbsent(key, value) }
        return RenameMapping(merged)
    }

    fun toJson(): String = Gson().toJson(forward)

    companion object {
        private val gson = Gson()

        fun fromJson(json: String): RenameMapping {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
            return RenameMapping(map)
        }

        @JvmStatic
        fun fromForward(forward: Map<String, String>): RenameMapping = RenameMapping(forward)

        fun build(
            candidates: Set<String>,
            seed: Int,
            excludePatterns: List<String>,
            salt: String = "component-rename",
        ): RenameMapping {
            val random = SeedRandom.create(seed, salt)
            val usedNames = mutableSetOf<String>()
            val forward = linkedMapOf<String, String>()

            candidates.sorted().forEach { original ->
                if (shouldExclude(original, excludePatterns)) return@forEach
                var obfuscated: String
                do {
                    obfuscated = ObfuscateNaming.nextClassName(random)
                } while (!usedNames.add(obfuscated))
                forward[original] = obfuscated
            }
            return RenameMapping(forward)
        }

        private fun shouldExclude(original: String, patterns: List<String>): Boolean =
            patterns.any { pattern ->
                pattern.replace(".", "\\.")
                    .replace("*", ".*")
                    .toRegex()
                    .containsMatchIn(original)
            }
    }
}

internal data class ProjectSourceIndex(
    val declaredClasses: Set<String>,
    val componentCandidates: Set<String>,
    val referencedSuperTypes: Set<String>,
) {
    fun mergedWith(other: ProjectSourceIndex): ProjectSourceIndex = ProjectSourceIndex(
        declaredClasses = declaredClasses + other.declaredClasses,
        componentCandidates = componentCandidates + other.componentCandidates,
        referencedSuperTypes = referencedSuperTypes + other.referencedSuperTypes,
    )

    companion object {
        val EMPTY = ProjectSourceIndex(emptySet(), emptySet(), emptySet())
    }
}

internal object ComponentScanner {

    private val KOTLIN_COMPONENT_REGEX = Regex(
        """class\s+(\w+)\s*(?:\([^)]*\))?\s*:\s*([^{]+)""",
    )
    private val JAVA_EXTENDS_REGEX = Regex(
        """class\s+(\w+)\s+(?:extends|implements)\s+([^{;]+)""",
    )
    private val CLASS_DECLARATION_REGEX = Regex(
        """\b(?:class|object|interface|record|enum)\s+([A-Za-z_]\w*)""",
    )
    private val XML_COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val ANDROID_NAME_ATTRIBUTE = Regex(
        """\bandroid:name\s*=\s*(["'])([^"']+)\1""",
        RegexOption.IGNORE_CASE,
    )
    private val FRAGMENT_CLASS_ATTRIBUTE = Regex(
        """<(?:fragment|androidx\.fragment\.app\.FragmentContainerView)\b[^>]*\bclass\s*=\s*(["'])([^"']+)\1""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val MANIFEST_COMPONENT_TAGS = listOf(
        "application",
        "activity",
        "activity-alias",
        "service",
        "receiver",
        "provider",
    )

    fun scanModule(
        namespace: String?,
        @Suppress("UNUSED_PARAMETER")
        sourceRoots: Iterable<File>,
        manifestFiles: Iterable<File>,
    ): Set<String> = scanManifests(namespace, manifestFiles)

    fun indexSources(
        namespace: String?,
        sourceRoots: Iterable<File>,
    ): ProjectSourceIndex {
        val declaredClasses = linkedSetOf<String>()
        val referencedSuperTypes = linkedSetOf<String>()
        val seenFiles = linkedSetOf<File>()

        sourceRoots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val normalizedFile = file.absoluteFile.normalize()
                    if (!seenFiles.add(normalizedFile)) return@forEach
                    val source = maskCommentsAndLiterals(file.readText())
                    val pkg = PACKAGE_REGEX.find(source)?.groupValues?.get(1) ?: namespace

                    if (pkg != null) {
                        topLevelClassDeclarations(source).forEach { match ->
                            declaredClasses += "$pkg.${match.groupValues[1]}"
                        }
                    }
                    KOTLIN_COMPONENT_REGEX.findAll(source).forEach { match ->
                        val supers = match.groupValues[2]
                        referencedSuperTypes += extractSuperTypes(supers)
                    }
                    JAVA_EXTENDS_REGEX.findAll(source).forEach { match ->
                        val supers = match.groupValues[2]
                        referencedSuperTypes += extractSuperTypes(supers)
                    }
                }
        }
        return ProjectSourceIndex(
            declaredClasses = declaredClasses,
            componentCandidates = emptySet(),
            referencedSuperTypes = referencedSuperTypes,
        )
    }

    fun scanManifests(
        namespace: String?,
        manifestFiles: Iterable<File>,
    ): Set<String> {
        val result = linkedSetOf<String>()
        manifestFiles.forEach { manifest ->
            if (manifest.isFile) result += scanManifest(manifest, namespace)
        }
        return result.filterTo(linkedSetOf(), ::isRenameCandidate)
    }

    /**
     * 扫描 layout* 与 navigation 中的运行时组件类名引用。
     * 源码继承关系只用于确认项目类和父类锚点，不再直接产生候选。
     */
    fun scanRuntimeResourceXml(
        namespace: String?,
        resourceRoots: Iterable<File>,
    ): Set<String> {
        val result = linkedSetOf<String>()
        RuntimeXmlResourceFiles.scan(resourceRoots, includeNavigation = true).forEach { file ->
            val text = file.readText().replace(XML_COMMENT, "")
            ANDROID_NAME_ATTRIBUTE.findAll(text).forEach { match ->
                result += toFqcn(match.groupValues[2], namespace)
            }
            FRAGMENT_CLASS_ATTRIBUTE.findAll(text).forEach { match ->
                result += toFqcn(match.groupValues[2], namespace)
            }
        }
        return result.filterTo(linkedSetOf(), ::isRenameCandidate)
    }

    fun collectProjectClasses(
        namespace: String?,
        sourceRoots: Iterable<File>,
    ): Set<String> = indexSources(namespace, sourceRoots).declaredClasses

    fun filterProjectClasses(
        candidates: Set<String>,
        projectClasses: Set<String>,
    ): Set<String> = candidates.filterTo(linkedSetOf()) { candidate ->
        isProjectClass(candidate, projectClasses)
    }

    fun isProjectClass(
        fqcn: String,
        projectClasses: Set<String>,
    ): Boolean = fqcn in projectClasses || fqcn.substringBefore('$') in projectClasses

    private fun topLevelClassDeclarations(source: String): Sequence<MatchResult> {
        val depthAt = IntArray(source.length)
        var depth = 0
        source.forEachIndexed { index, char ->
            depthAt[index] = depth
            when (char) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
        }
        return CLASS_DECLARATION_REGEX.findAll(source)
            .filter { match -> depthAt[match.range.first] == 0 }
    }

    private fun maskCommentsAndLiterals(source: String): String {
        val masked = source.toCharArray()
        var index = 0
        var blockCommentDepth = 0
        var state = SourceMaskState.CODE
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                SourceMaskState.CODE -> when {
                    current == '/' && next == '/' -> {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        index += 2
                        state = SourceMaskState.LINE_COMMENT
                    }
                    current == '/' && next == '*' -> {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        index += 2
                        blockCommentDepth = 1
                        state = SourceMaskState.BLOCK_COMMENT
                    }
                    source.startsWith("\"\"\"", index) -> {
                        repeat(3) { offset -> masked[index + offset] = ' ' }
                        index += 3
                        state = SourceMaskState.RAW_STRING
                    }
                    current == '"' -> {
                        masked[index++] = ' '
                        state = SourceMaskState.STRING
                    }
                    current == '\'' -> {
                        masked[index++] = ' '
                        state = SourceMaskState.CHAR
                    }
                    else -> index++
                }
                SourceMaskState.LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') {
                        state = SourceMaskState.CODE
                    } else {
                        masked[index] = ' '
                    }
                    index++
                }
                SourceMaskState.BLOCK_COMMENT -> when {
                    current == '/' && next == '*' -> {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        index += 2
                        blockCommentDepth++
                    }
                    current == '*' && next == '/' -> {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        index += 2
                        blockCommentDepth--
                        if (blockCommentDepth == 0) state = SourceMaskState.CODE
                    }
                    else -> {
                        if (current != '\n' && current != '\r') masked[index] = ' '
                        index++
                    }
                }
                SourceMaskState.RAW_STRING -> {
                    if (source.startsWith("\"\"\"", index)) {
                        repeat(3) { offset -> masked[index + offset] = ' ' }
                        index += 3
                        state = SourceMaskState.CODE
                    } else {
                        if (current != '\n' && current != '\r') masked[index] = ' '
                        index++
                    }
                }
                SourceMaskState.STRING,
                SourceMaskState.CHAR,
                -> {
                    val terminator = if (state == SourceMaskState.STRING) '"' else '\''
                    when {
                        current == '\\' && next != null -> {
                            masked[index] = ' '
                            if (next != '\n' && next != '\r') masked[index + 1] = ' '
                            index += 2
                        }
                        current == terminator -> {
                            masked[index++] = ' '
                            state = SourceMaskState.CODE
                        }
                        else -> {
                            if (current != '\n' && current != '\r') masked[index] = ' '
                            index++
                        }
                    }
                }
            }
        }
        return masked.concatToString()
    }

    private enum class SourceMaskState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        RAW_STRING,
        STRING,
        CHAR,
    }

    /**
     * 作为其他组件/Fragment 父类被源码引用的类不能参与 component 重命名，
     * 否则下游模块 Kotlin 编译仍引用原名会失败。
     */
    fun filterSupertypeAnchors(
        candidates: Set<String>,
        sourceRoots: Iterable<File>,
    ): Set<String> = filterSupertypeAnchors(
        candidates = candidates,
        sourceIndex = indexSources(namespace = null, sourceRoots = sourceRoots),
    )

    fun filterSupertypeAnchors(
        candidates: Set<String>,
        sourceIndex: ProjectSourceIndex,
    ): Set<String> {
        if (candidates.isEmpty()) return candidates
        val candidateBySimple = candidates.groupBy { it.substringAfterLast('.') }
        val usedAsSuper = linkedSetOf<String>()
        sourceIndex.referencedSuperTypes.forEach { referencedType ->
            when {
                referencedType in candidates -> usedAsSuper += referencedType
                !referencedType.contains('.') -> {
                    candidateBySimple[referencedType]?.let(usedAsSuper::addAll)
                }
            }
        }
        return candidates - usedAsSuper
    }

    private fun extractSuperTypes(supers: String): Set<String> =
        supers.split(',')
            .flatMap { token -> token.split(Regex("""\s+(?:extends|implements)\s+""")) }
            .map { token ->
                token.trim()
                    .substringBefore('(')
                    .substringBefore('<')
                    .removeSuffix("?")
                    .trim()
            }
            .filterTo(linkedSetOf()) { it.isNotEmpty() }

    private fun scanManifest(manifest: File, namespace: String?): Set<String> {
        val text = manifest.readText()
        val result = linkedSetOf<String>()
        MANIFEST_COMPONENT_TAGS.forEach { tag ->
            Regex("""<$tag\b[\s\S]*?android:name="([^"]+)"""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .forEach { match ->
                    result += toFqcn(match.groupValues[1], namespace)
                }
        }
        return result
    }

    private fun isRenameCandidate(fqcn: String): Boolean {
        if (!fqcn.contains('.') || fqcn.startsWith("android.") || fqcn.startsWith("androidx.")) {
            return false
        }
        val simpleName = fqcn.substringAfterLast('.')
        // 过滤 meta-data 风格常量名（如 APPLICATION_ID、MESSAGING_EVENT）
        if (simpleName.contains('_') && simpleName == simpleName.uppercase()) return false
        return simpleName.first().isUpperCase()
    }

    private fun toFqcn(name: String, namespace: String?): String = when {
        name.startsWith('.') -> "${namespace.orEmpty()}${name}"
        name.contains('.') -> name
        namespace.isNullOrBlank() -> name
        else -> "$namespace.$name"
    }

    private val PACKAGE_REGEX = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
}
