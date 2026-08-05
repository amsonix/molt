package io.github.amsonix.molt.internal.mapping

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class R8MappingAliasExpanderTest {

    @Test
    fun expand_linksR8AliasToShellTarget() {
        val shell = RenameMapping.fromForward(
            mapOf("com.example.app.view.web.WebViewMainFragment" to "p8.o1"),
        )
        val r8File = File.createTempFile("r8-mapping", ".txt").apply {
            writeText(
                """
                com.example.app.view.web.WebViewMainFragment -> com.example.app.view.web.a:
                    void onCreate() -> onCreate
                """.trimIndent(),
            )
            deleteOnExit()
        }

        val expanded = R8MappingAliasExpander.expand(shell, r8File)!!

        assertEquals("p8.o1", expanded.resolve("com.example.app.view.web.WebViewMainFragment"))
        assertEquals("p8.o1", expanded.resolve("com.example.app.view.web.a"))
    }

    @Test
    fun expand_resolvesInnerClassThroughShellMapping() {
        val shell = RenameMapping.fromForward(
            mapOf("com.example.Outer" to "x.y.z"),
        )
        val r8File = File.createTempFile("r8-mapping-inner", ".txt").apply {
            writeText("com.example.Outer${'$'}1 -> com.example.a${'$'}b:\n")
            deleteOnExit()
        }

        val expanded = R8MappingAliasExpander.expand(shell, r8File)!!

        assertEquals("x.y.z${'$'}1", expanded.resolve("com.example.Outer${'$'}1"))
        assertEquals("x.y.z${'$'}1", expanded.resolve("com.example.a${'$'}b"))
    }

    @Test
    fun expand_returnsNullInNullOut() {
        assertNull(R8MappingAliasExpander.expand(null, null))
    }
}
