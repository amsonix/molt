package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.util.SeedRandom
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * assets 保护（轻量扰动）：对 APK/AAB 内 `assets/` 文本文件注入假字段/注释，并注入 seed 派生的假文件。
 * 不加密、不改运行时读取路径——破坏内容/结构指纹的同时保持零运行时风险。
 */
internal data class AssetsProtectionConfig(
    val seed: Int,
    val filePatterns: List<String>,
    val junkFileCount: Int,
    val excludePatterns: List<String>,
    /** APK 为 `assets/`，AAB 为 `base/assets/`。 */
    val assetsPrefix: String,
)

internal object AssetsProtectionEngine {

    private val JSON_OBJECT = Regex("""^\s*\{[\s\S]*\}\s*$""")
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun patchZipInPlace(zipFile: File, config: AssetsProtectionConfig) {
        val temp = File.createTempFile("molt-assets-protect", ".zip", zipFile.parentFile)
        try {
            patch(zipFile, temp, config)
            temp.copyTo(zipFile, overwrite = true)
        } finally {
            temp.delete()
        }
    }

    private fun patch(input: File, output: File, config: AssetsProtectionConfig) {
        val random = SeedRandom.create(config.seed, "assets-protect")
        val junkName = "molt_junk_${Math.abs(random.nextInt() % 100000)}"
        val junkEntryPrefix = "${config.assetsPrefix}$junkName/"
        val junkFiles = (0 until config.junkFileCount).map { index ->
            junkEntryPrefix + randomAssetName(random) to randomJunkContent(random)
        }

        ZipFile(input).use { zipIn ->
            ZipOutputStream(output.outputStream().buffered()).use { zipOut ->
                var patched = 0
                var injected = 0
                zipIn.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) {
                        ZipEntryWriter.copy(zipOut, zipIn, entry, entry.name)
                        return@forEach
                    }
                    val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                    if (entry.name.startsWith(config.assetsPrefix) &&
                        !entry.name.startsWith(junkEntryPrefix)
                    ) {
                        val patchedBytes = patchAssetEntry(entry.name, bytes, config, random)
                        if (patchedBytes != null) {
                            ZipEntryWriter.writeBytes(
                                zipOut = zipOut,
                                source = entry,
                                outputName = entry.name,
                                bytes = patchedBytes,
                                contentsChanged = true,
                            )
                            patched++
                            return@forEach
                        }
                    }
                    ZipEntryWriter.writeBytes(
                        zipOut = zipOut,
                        source = entry,
                        outputName = entry.name,
                        bytes = bytes,
                        contentsChanged = false,
                    )
                }
                // 追加假文件（排序插入，保持 zip 确定性）。
                junkFiles.sortedBy { it.first }.forEach { (name, content) ->
                    val entry = java.util.zip.ZipEntry(name)
                    entry.method = java.util.zip.ZipEntry.DEFLATED
                    zipOut.putNextEntry(entry)
                    zipOut.write(content)
                    zipOut.closeEntry()
                    injected++
                }
                java.util.logging.Logger.getLogger(AssetsProtectionEngine::class.java.name)
                    .info("molt: assets protection patched=$patched injected=$injected")
            }
        }
    }

    /** 命中 filePatterns 且可安全扰动的文本文件：返回改写后内容；不可扰动返回 null。 */
    private fun patchAssetEntry(
        entryName: String,
        bytes: ByteArray,
        config: AssetsProtectionConfig,
        random: java.util.Random,
    ): ByteArray? {
        if (matchesAny(entryName, config.excludePatterns)) return null
        if (!matchesAny(entryName, config.filePatterns)) return null
        if (!looksLikeText(bytes)) return null

        val text = decodeUtf8(bytes) ?: return null
        return when {
            isJsonObject(text) -> injectJsonField(text, random)?.encodeToByteArray()
            text.trimEnd().endsWith(">") -> (text.trimEnd() + "\n<!-- molt -->\n").encodeToByteArray()
            else -> null
        }
    }

    /** JSON 顶层对象：在最后一个 `}` 前注入假字段（不解析，仅结构校验）。 */
    private fun injectJsonField(text: String, random: java.util.Random): String? {
        val lastBrace = text.lastIndexOf('}')
        if (lastBrace <= 0) return null
        val head = text.substring(0, lastBrace).trimEnd()
        if (head.isEmpty() || head.endsWith(':')) return null
        val field = "molt_${randomFieldName(random)}"
        val value = randomFieldValue(random)
        val separator = if (head.endsWith('{')) "" else ","
        return text.substring(0, lastBrace) + separator + "\"$field\": \"$value\"" + text.substring(lastBrace)
    }

    private fun isJsonObject(text: String): Boolean = JSON_OBJECT.matches(text)

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.size == 0) return false
        if (bytes.size > 1_000_000) return false
        var textBytes = bytes
        if (textBytes.size >= 3 && textBytes.copyOfRange(0, 3).contentEquals(UTF8_BOM)) {
            textBytes = textBytes.copyOfRange(3, textBytes.size)
        }
        var printable = 0
        for (byte in textBytes) {
            val value = byte.toInt() and 0xFF
            if (value == 0) return false
            if (value == 9 || value == 10 || value == 13 || value in 0x20..0x7E) {
                printable++
            }
        }
        return printable.toDouble() / textBytes.size >= 0.9
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        val start = if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(UTF8_BOM)) 3 else 0
        String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }.getOrNull()

    private fun randomFieldName(random: java.util.Random): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        return buildString {
            repeat(4 + random.nextInt(6)) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun randomFieldValue(random: java.util.Random): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(8 + random.nextInt(16)) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun randomAssetName(random: java.util.Random): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val name = buildString {
            repeat(6 + random.nextInt(8)) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
        return "$name.txt"
    }

    private fun randomJunkContent(random: java.util.Random): ByteArray {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789 \n"
        return buildString {
            repeat(200 + random.nextInt(400)) { append(alphabet[random.nextInt(alphabet.length)]) }
        }.encodeToByteArray()
    }

    private fun matchesAny(entryName: String, patterns: List<String>): Boolean {
        val fileName = entryName.substringAfterLast('/')
        return patterns.any { pattern ->
            val target = if (pattern.contains('/')) entryName else fileName
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            Regex(regex).matches(target)
        }
    }
}
