package io.github.amsonix.molt.internal.util

import java.util.Random

internal object SeedRandom {

    fun create(seed: Int): Random = Random(seed.toLong())

    fun create(seed: Int, salt: String): Random = Random(seed.toLong() xor salt.hashCode().toLong())
}

internal object ObfuscateNaming {

    /** 基于 seed 生成短随机段名，如 a3.b7 */
    fun nextClassName(random: Random): String {
        val segmentCount = 2 + random.nextInt(2)
        return (0 until segmentCount).joinToString(".") { segment(random) }
    }

    /** layout/drawable 等资源短名 */
    fun nextResourceName(random: Random, prefix: Char = 'r'): String {
        val length = 2 + random.nextInt(3)
        return buildString {
            append(prefix)
            repeat(length) { append(('a'.code + random.nextInt(26)).toChar()) }
            append(random.nextInt(10))
        }
    }

    private fun segment(random: Random): String {
        val length = 1 + random.nextInt(2)
        return buildString {
            append(('a'.code + random.nextInt(26)).toChar())
            repeat(length - 1) { append(('a'.code + random.nextInt(26)).toChar()) }
            append(random.nextInt(10))
        }
    }
}

internal fun variantCapitalizedName(variantName: String): String =
    variantName.split(Regex("[^a-zA-Z0-9]")).joinToString("") { part ->
        part.replaceFirstChar { ch -> ch.uppercaseChar() }
    }

internal fun normalizePath(path: String): String = path.replace('\\', '/')
