package io.github.amsonix.molt.internal.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ClassRenameProtectionScannerTest {

    @Test
    fun scan_collectsTopLevelKeepClassesAndExactStringFqcns() {
        val sourceRoot = Files.createTempDirectory("class-protection-scanner").toFile()
        try {
            val tripleQuote = "\"\"\""
            sourceRoot.resolve("KotlinSource.kt").writeText(
                """
                package com.example.keep

                @Keep
                class KotlinKept

                @androidx.annotation.Keep
                data class QualifiedKept(val value: Int)

                class Outer {
                    @Keep
                    class NestedKept
                }

                val plain = "com.example.reflect.Plain"
                val escaped = "com.example.reflect.Outer\${'$'}Inner"
                val unicode = "com.example.reflect.Unicode\u0043lass"
                val raw = ${tripleQuote}com.example.reflect.RawTarget${tripleQuote}
                val template = "com.example.reflect.${'$'}dynamic"
                val unrelated = "prefix com.example.reflect.NotExact suffix"

                // @Keep class CommentedKeep
                // val ignored = "com.example.comment.LineComment"
                /*
                 * @Keep class BlockCommentKeep
                 * "com.example.comment.BlockComment"
                 */
                """.trimIndent(),
            )
            sourceRoot.resolve("JavaSource.java").writeText(
                """
                package com.example.java;

                @androidx.annotation.Keep
                public final class JavaKept {
                    String target = "com.example.reflect.JavaTarget";
                    String unrelated = "https://com.example.reflect.NotAClass";
                }

                // @Keep class CommentedJavaKeep {}
                /* String ignored = "com.example.comment.JavaComment"; */
                """.trimIndent(),
            )

            val result = ClassRenameProtectionScanner.scan(listOf(sourceRoot))

            assertEquals(
                setOf(
                    "com.example.keep.KotlinKept",
                    "com.example.keep.QualifiedKept",
                    "com.example.java.JavaKept",
                    "com.example.reflect.Plain",
                    "com.example.reflect.Outer\$Inner",
                    "com.example.reflect.UnicodeClass",
                    "com.example.reflect.RawTarget",
                    "com.example.reflect.JavaTarget",
                ),
                result,
            )
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    @Test
    fun scan_ignoresCommentsNestedKeepAndNonExactStrings() {
        val sourceRoot = Files.createTempDirectory("class-protection-comments").toFile()
        try {
            sourceRoot.resolve("Ignored.kt").writeText(
                """
                package com.example

                class Container {
                    @Keep class Nested
                }

                val sentence = "load com.example.NotExact now"
                val path = "com/example/PathClass"
                // "com.example.LineComment"
                /* "com.example.BlockComment" */
                """.trimIndent(),
            )

            val result = ClassRenameProtectionScanner.scan(listOf(sourceRoot))

            assertTrue(result.isEmpty())
            assertFalse(result.contains("com.example.Nested"))
        } finally {
            sourceRoot.deleteRecursively()
        }
    }
}
