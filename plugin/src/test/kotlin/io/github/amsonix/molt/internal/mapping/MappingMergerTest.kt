package io.github.amsonix.molt.internal.mapping

import io.github.amsonix.molt.internal.rename.RenameMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MappingMergerTest {

    @Test
    fun compose_rewritesClassHeaderAndPreservesBlockContents() {
        val r8Mapping = """
            # compiler: R8

            com.example.Hit -> a:
                int count -> b
                1:3:void run(int):9:11 -> c
                # {"id":"sourceFile","fileName":"Hit.kt"}
            com.example.Unchanged -> d:
                boolean enabled -> e
        """.trimIndent()
        val shellMapping = RenameMapping.fromForward(
            mapOf("com.example.Hit" to "shell.runtime.Hit"),
        )

        val result = MappingMerger.compose(r8Mapping, shellMapping)

        assertEquals(
            """
                # compiler: R8

                com.example.Hit -> shell.runtime.Hit:
                    int count -> b
                    1:3:void run(int):9:11 -> c
                    # {"id":"sourceFile","fileName":"Hit.kt"}
                com.example.Unchanged -> d:
                    boolean enabled -> e
            """.trimIndent(),
            result,
        )
        assertFalse(result.contains("a -> shell.runtime.Hit:"))
    }

    @Test
    fun compose_rewritesIdentityClassMapping() {
        val r8Mapping = """
            com.example.Kept -> com.example.Kept:
                void onCreate() -> onCreate
        """.trimIndent()
        val shellMapping = RenameMapping.fromForward(
            mapOf("com.example.Kept" to "shell.runtime.Kept"),
        )

        assertEquals(
            """
                com.example.Kept -> shell.runtime.Kept:
                    void onCreate() -> onCreate
            """.trimIndent(),
            MappingMerger.compose(r8Mapping, shellMapping),
        )
    }

    @Test
    fun compose_rewritesResidualSignaturesToRuntimeTypes() {
        val r8Mapping = """
            com.example.Hit -> a:
                void accept(com.example.Other) -> b
                  # {"id":"com.android.tools.r8.residualsignature","signature":"(Lb;[La;)La;"}
                  # {"id":"custom.metadata","signature":"La;"}
            com.example.Other -> b:
        """.trimIndent()
        val shellMapping = RenameMapping.fromForward(
            mapOf(
                "com.example.Hit" to "b",
                "com.example.Other" to "c",
            ),
        )

        val result = MappingMerger.compose(r8Mapping, shellMapping)

        assertEquals(
            """
                com.example.Hit -> b:
                    void accept(com.example.Other) -> b
                      # {"id":"com.android.tools.r8.residualsignature","signature":"(Lc;[Lb;)Lb;"}
                      # {"id":"custom.metadata","signature":"La;"}
                com.example.Other -> c:
            """.trimIndent(),
            result,
        )
        assertEquals(result, MappingMerger.compose(result, shellMapping))
    }

    @Test
    fun compose_appendsMissingClassesInStableOrderAndIsIdempotent() {
        val r8Mapping = "# mapping\ncom.example.Present -> a:"
        val shellMapping = RenameMapping.fromForward(
            linkedMapOf(
                "com.example.ZMissing" to "shell.Z",
                "com.example.Present" to "shell.Present",
                "com.example.AMissing" to "shell.A",
            ),
        )

        val first = MappingMerger.compose(r8Mapping, shellMapping)

        assertEquals(
            """
                # mapping
                com.example.Present -> shell.Present:
                com.example.AMissing -> shell.A:
                com.example.ZMissing -> shell.Z:
            """.trimIndent(),
            first,
        )
        assertEquals(1, first.lineSequence().count { it.startsWith("com.example.Present ->") })
        assertEquals(first, MappingMerger.compose(first, shellMapping))
    }

    @Test
    fun compose_usesRenameMappingResolveForInnerClasses() {
        val r8Mapping = """
            com.example.Outer -> a:
            com.example.Outer${'$'}Inner -> b:
                void invoke() -> c
        """.trimIndent()
        val shellMapping = RenameMapping.fromForward(
            mapOf("com.example.Outer" to "shell.runtime.Outer"),
        )

        assertEquals(
            """
                com.example.Outer -> shell.runtime.Outer:
                com.example.Outer${'$'}Inner -> shell.runtime.Outer${'$'}Inner:
                    void invoke() -> c
            """.trimIndent(),
            MappingMerger.compose(r8Mapping, shellMapping),
        )
    }

}
