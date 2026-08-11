package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.rename.RenameMapping
import io.github.amsonix.molt.internal.util.GlobMatcher
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * R8 之后对 APK/AAB zip 统一补丁。
 *
 * - DEX：组件 + 自定义 View 共用 [DexInPlaceRenameEngine]（dexlib2 完整重建）
 * - Manifest：仅组件 mapping
 * - res XML：组件 + View mapping，覆盖 layout 与 navigation 引用
 */
internal object ZipPostR8RenameProcessor {

    private val DEX_ENTRY = Regex("(^|.*/)classes\\d*\\.dex$")
    private val MANIFEST_ENTRIES = setOf(
        "base/manifest/AndroidManifest.xml",
        "manifest/AndroidManifest.xml",
        "AndroidManifest.xml",
    )

    data class Config(
        val componentMapping: RenameMapping? = null,
        val viewMapping: RenameMapping? = null,
        val axmlStrictMode: Boolean = false,
        val projectPackagePrefixes: List<String> = emptyList(),
        /** glob：匹配 zip entry 名则跳过 layout XML 改写与残留类名校验。 */
        val excludeResXmlEntryPatterns: List<String> = emptyList(),
        /** post-R8 DEX 字符串加密；null = 关闭。 */
        val stringEncrypt: DexStringEncryptionConfig? = null,
        /** post-R8 DEX 垃圾指令注入；null = 关闭。 */
        val dexPerturb: DexPerturbationConfig? = null,
        /** post-R8 AssetManager.open 调用点改写（assets 加密）；null = 关闭。 */
        val assetsEncrypt: AssetsEncryptConfig? = null,
        /** 构建期告警/信息回调（Gradle logger；java.util.logging 在 Gradle 不可见）。 */
        val onWarning: (String) -> Unit = {},
        val onInfo: (String) -> Unit = {},
    )

    data class Result(
        val dexFiles: Int,
        val componentManifestFiles: Int,
        val layoutFiles: Int,
        val xmlFiles: Int = 0,
        val xmlReplacementCount: Int = 0,
        val xmlFailures: List<XmlFailure> = emptyList(),
    ) {
        val xmlFailureCount: Int
            get() = xmlFailures.size
    }

    data class XmlFailure(
        val entryName: String,
        val formatStatus: ProtoXmlViewClassReplacer.FormatStatus,
        val reason: String,
    )

    fun processZip(input: File, output: File, config: Config): Result {
        require(input.isFile) { "input zip not found: ${input.path}" }
        require(input.canonicalFile != output.canonicalFile) {
            "input and output zip must differ; use processZipInPlace for in-place updates"
        }
        output.parentFile?.mkdirs()

        val componentMapping = config.componentMapping?.takeIf { it.entries().isNotEmpty() }
        val viewMapping = config.viewMapping?.takeIf { it.entries().isNotEmpty() }
        val dexMapping = mergeDexMapping(componentMapping, viewMapping)
        val resourceXmlMapping = mergeDexMapping(componentMapping, viewMapping)
        val stringEncrypt = config.stringEncrypt
        val dexPerturb = config.dexPerturb
        val assetsEncrypt = config.assetsEncrypt
        val dexWorkEnabled = dexMapping != null || stringEncrypt != null ||
            dexPerturb != null || assetsEncrypt != null

        if (!dexWorkEnabled && componentMapping == null && viewMapping == null) {
            input.copyTo(output, overwrite = true)
            return Result(0, 0, 0)
        }

        val dexEntries = if (dexWorkEnabled) loadDexEntries(input) else emptyMap()
        val dexRewritePlan = if (dexWorkEnabled) {
            buildDexRewritePlan(
                dexEntries.values.toList(),
                dexMapping ?: RenameMapping.fromForward(emptyMap()),
                config.projectPackagePrefixes,
            )
        } else {
            null
        }
        // 预计算实际加密文件集合：调用点改写只覆盖被加密的文件（清单外文件保持原样，
        // 否则 FogAssets 会对明文执行 XOR 输出乱码，如 .svga 动画损坏）。
        val encryptedAssetPaths: Set<String> = assetsEncrypt?.let { assetsConfig ->
            val zipIn = java.util.zip.ZipFile(input)
            try {
                // APK 为 assets/，AAB 为 base/assets/——按 zip 结构自适应。
                val assetsPrefix = if (zipIn.getEntry("base/") != null) "base/assets/" else "assets/"
                io.github.amsonix.molt.internal.bundle.ZipAssetEncryptor
                    .computeEncryptedPaths(zipIn, assetsConfig, assetsPrefix)
            } finally {
                zipIn.close()
            }
        } ?: emptySet()
        val patchedDexEntries = if (dexWorkEnabled && dexRewritePlan != null) {
            dexEntries.mapValues { (_, bytes) ->
                DexInPlaceRenameEngine.remapBytes(
                    bytes,
                    dexMapping ?: RenameMapping.fromForward(emptyMap()),
                    dexRewritePlan,
                    stringEncrypt,
                    config.dexPerturb,
                    config.assetsEncrypt,
                    encryptedAssetPaths,
                )
            }
        } else {
            emptyMap()
        }

        var dexFiles = 0
        var componentManifestFiles = 0
        var layoutFiles = 0
        var xmlFiles = 0
        var xmlReplacementCount = 0
        val xmlFailures = mutableListOf<XmlFailure>()
        ZipFile(input).use { zipIn ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zipOut ->
                zipIn.entries().asSequence().forEach { entry ->
                    when {
                        dexWorkEnabled && dexRewritePlan != null && DEX_ENTRY.matches(entry.name) -> {
                            val patchedBytes = patchedDexEntries[entry.name]
                                ?: throw IllegalStateException("missing patched dex for ${entry.name}")
                            val originalBytes = zipIn.getInputStream(entry).use { it.readBytes() }
                            val contentsChanged = !patchedBytes.contentEquals(originalBytes)
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = patchedBytes,
                                contentsChanged = contentsChanged,
                            )
                            dexFiles++
                        }
                        componentMapping != null && entry.name in MANIFEST_ENTRIES -> {
                            val originalBytes = zipIn.getInputStream(entry).use { it.readBytes() }
                            val rewrite = rewriteXml(originalBytes, componentMapping)
                            handleXmlFailure(
                                entry.name, rewrite, config.axmlStrictMode, xmlFailures, componentMapping,
                            )
                            verifyNoResidualRuntimeNames(entry.name, rewrite, componentMapping)
                            val patchedBytes = rewrite.bytes
                            val contentsChanged = !patchedBytes.contentEquals(originalBytes)
                            if (contentsChanged) {
                                componentManifestFiles++
                            }
                            xmlFiles++
                            xmlReplacementCount += rewrite.replacementCount
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = patchedBytes,
                                contentsChanged = contentsChanged,
                            )
                        }
                        resourceXmlMapping != null &&
                            entry.name.endsWith(".xml") &&
                            isResXmlEntry(entry.name) -> {
                            if (shouldSkipResXml(entry.name, config.excludeResXmlEntryPatterns)) {
                                ZipEntryWriter.copy(zipOut, zipIn, entry, entry.name)
                                return@forEach
                            }
                            val originalBytes = zipIn.getInputStream(entry).use { it.readBytes() }
                            val rewrite = rewriteXml(originalBytes, resourceXmlMapping)
                            handleXmlFailure(
                                entry.name, rewrite, config.axmlStrictMode, xmlFailures, resourceXmlMapping,
                            )
                            verifyNoResidualRuntimeNames(entry.name, rewrite, resourceXmlMapping)
                            val patchedBytes = rewrite.bytes
                            val contentsChanged = !patchedBytes.contentEquals(originalBytes)
                            if (contentsChanged) {
                                layoutFiles++
                            }
                            xmlFiles++
                            xmlReplacementCount += rewrite.replacementCount
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = patchedBytes,
                                contentsChanged = contentsChanged,
                            )
                        }
                        else -> ZipEntryWriter.copy(zipOut, zipIn, entry, entry.name)
                    }
                }
            }
        }
        config.onInfo(
            "post-R8 XML rewrite files=$xmlFiles, replacements=$xmlReplacementCount, failures=${xmlFailures.size}",
        )
        if (xmlFailures.isNotEmpty()) {
            config.onWarning(
                "post-R8 XML rewrite failures=${xmlFailures.size}, replacements=$xmlReplacementCount: " +
                    xmlFailures.joinToString { failure ->
                        "${failure.entryName}(${failure.formatStatus}: ${failure.reason})"
                    },
            )
        }
        return Result(
            dexFiles = dexFiles,
            componentManifestFiles = componentManifestFiles,
            layoutFiles = layoutFiles,
            xmlFiles = xmlFiles,
            xmlReplacementCount = xmlReplacementCount,
            xmlFailures = xmlFailures,
        )
    }

    private fun loadDexEntries(input: File): Map<String, ByteArray> =
        ZipFile(input).use { zipIn ->
            zipIn.entries().asSequence()
                .filter { entry -> DEX_ENTRY.matches(entry.name) }
                .associate { entry ->
                    entry.name to zipIn.getInputStream(entry).use { stream -> stream.readBytes() }
                }
        }

    private fun buildDexRewritePlan(
        dexBytes: List<ByteArray>,
        dexMapping: RenameMapping?,
        projectPackagePrefixes: List<String>,
    ): DexRewritePlan? {
        if (dexMapping == null || dexBytes.isEmpty()) return null
        return try {
            DexInPlaceRenameEngine.buildRewritePlan(dexBytes, dexMapping, projectPackagePrefixes)
        } catch (e: Exception) {
            throw RuntimeException("post-R8 global dex rewrite plan failed", e)
        }
    }

    private fun mergeDexMapping(
        componentMapping: RenameMapping?,
        viewMapping: RenameMapping?,
    ): RenameMapping? = when {
        componentMapping != null && viewMapping != null -> componentMapping.mergedWith(viewMapping)
        componentMapping != null -> componentMapping
        viewMapping != null -> viewMapping
        else -> null
    }?.takeIf { it.entries().isNotEmpty() }

    fun processZipInPlace(zipFile: File, config: Config): Result {
        val temp = File.createTempFile("shell-post-r8-rename", ".zip", zipFile.parentFile)
        try {
            val result = processZip(zipFile, temp, config)
            temp.copyTo(zipFile, overwrite = true)
            return result
        } finally {
            temp.delete()
        }
    }

    private fun rewriteXml(
        bytes: ByteArray,
        mapping: RenameMapping,
    ): ProtoXmlViewClassReplacer.RewriteResult =
        when {
            isBinaryXml(bytes) -> BinaryXmlViewClassReplacer.rewrite(bytes, mapping)
            looksLikeTextXml(bytes) -> TextXmlViewClassReplacer.rewrite(bytes, mapping)
            else -> ProtoXmlViewClassReplacer.rewrite(bytes, mapping)
        }

    private fun looksLikeTextXml(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 64)
        for (index in 0 until limit) {
            val value = bytes[index].toInt() and 0xFF
            if (!value.toChar().isWhitespace()) {
                return value.toChar() == '<'
            }
        }
        return false
    }

    private fun handleXmlFailure(
        entryName: String,
        rewrite: ProtoXmlViewClassReplacer.RewriteResult,
        strictMode: Boolean,
        failures: MutableList<XmlFailure>,
        mapping: RenameMapping,
    ) {
        if (rewrite.formatStatus == ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED) return
        val failure = XmlFailure(
            entryName = entryName,
            formatStatus = rewrite.formatStatus,
            reason = rewrite.failureReason ?: "unknown XML rewrite failure",
        )
        if (strictMode && containsResidualRuntimeNames(rewrite.bytes, mapping)) {
            error(
                "post-R8 XML rewrite failed: entry=${failure.entryName}, " +
                    "status=${failure.formatStatus}, reason=${failure.reason}",
            )
        }
        failures += failure
    }

    private fun verifyNoResidualRuntimeNames(
        entryName: String,
        rewrite: ProtoXmlViewClassReplacer.RewriteResult,
        mapping: RenameMapping,
    ) {
        when (rewrite.formatStatus) {
            ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED -> {
                if (rewrite.replacementCount == 0) {
                    return
                }
                val secondPass = rewriteXml(rewrite.bytes, mapping)
                if (secondPass.formatStatus == ProtoXmlViewClassReplacer.FormatStatus.SUPPORTED &&
                    secondPass.replacementCount > 0
                ) {
                    error(
                        "post-R8 XML still contains old runtime class name: " +
                            "entry=$entryName, classes=relative runtime class reference",
                    )
                }
            }
            else -> verifyOriginalNamesAbsent(entryName, rewrite.bytes, mapping)
        }
    }

    private fun verifyOriginalNamesAbsent(
        entryName: String,
        bytes: ByteArray,
        mapping: RenameMapping,
    ) {
        val rawResiduals = findResidualRuntimeNames(bytes, mapping)
        if (rawResiduals.isEmpty()) return
        val detail = rawResiduals.take(5).joinToString()
        error("post-R8 XML still contains old runtime class name: entry=$entryName, classes=$detail")
    }

    private fun containsResidualRuntimeNames(bytes: ByteArray, mapping: RenameMapping): Boolean =
        findResidualRuntimeNames(bytes, mapping).isNotEmpty()

    private fun findResidualRuntimeNames(bytes: ByteArray, mapping: RenameMapping): List<String> =
        mapping.entries()
            .map { entry -> entry.original }
            .filter { original ->
                bytes.containsRuntimeName(original.toByteArray(Charsets.UTF_8), characterWidth = 1) ||
                    bytes.containsRuntimeName(original.toByteArray(Charsets.UTF_16LE), characterWidth = 2)
            }

    private fun isResXmlEntry(name: String): Boolean =
        name.startsWith("res/") || name.contains("/res/")

    private fun shouldSkipResXml(entryName: String, patterns: List<String>): Boolean =
        GlobMatcher.anyMatch(entryName, patterns)

    private fun isBinaryXml(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val type = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        return type == 0x0003
    }

    private fun ByteArray.containsRuntimeName(needle: ByteArray, characterWidth: Int): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            if (!regionMatches(start, needle)) continue
            val before = readAsciiCharacter(start - characterWidth, characterWidth)
            val after = readAsciiCharacter(start + needle.size, characterWidth)
            if (!isRuntimeNameCharacter(before) && !isRuntimeNameCharacter(after)) return true
        }
        return false
    }

    private fun ByteArray.regionMatches(start: Int, needle: ByteArray): Boolean =
        needle.indices.all { offset -> this[start + offset] == needle[offset] }

    private fun ByteArray.readAsciiCharacter(offset: Int, characterWidth: Int): Char? {
        if (offset < 0 || offset + characterWidth > size) return null
        val value = this[offset].toInt() and 0xFF
        if (characterWidth == 2 && this[offset + 1].toInt() != 0) return null
        return value.toChar()
    }

    private fun isRuntimeNameCharacter(character: Char?): Boolean =
        character != null && (character.isLetterOrDigit() || character == '_' || character == '$' || character == '.')
}
