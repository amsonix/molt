package io.github.amsonix.molt.internal.junk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.tools.ToolProvider

class JunkCodeGeneratorTest {

    @Test
    fun distributeCount_splitsRemainderAcrossFirstBuckets() {
        assertArrayEquals(intArrayOf(0, 0), JunkCodeGenerator.distributeCount(0, 2))
        assertArrayEquals(intArrayOf(2, 1), JunkCodeGenerator.distributeCount(3, 2))
        assertArrayEquals(intArrayOf(7, 7, 6, 6, 6), JunkCodeGenerator.distributeCount(32, 5))
    }

    @Test
    fun generate_writesUtilityClassesAcrossSubPackages() {
        val outputDir = tempOutputDir()
        try {
            val result = JunkCodeGenerator.generate(
                outputDir = outputDir,
                config = JunkCodeGenerator.Config(
                    packageCount = 2,
                    classCount = 3,
                    methodsPerClass = 2,
                    activityCountPerPackage = 0,
                    seed = 42,
                    packagePrefix = "com.example.junk",
                ),
            )

            assertEquals(3, result.utilityClassCount)
            assertEquals(0, result.activityClassCount)
            assertEquals(2, result.packageNames.size)
            assertTrue(result.packageNames.all { it.startsWith("com.example.junk.") })

            val javaFiles = File(outputDir, "java").walkTopDown().filter { it.extension == "java" }.toList()
            assertEquals(3, javaFiles.size)
            javaFiles.forEach { file ->
                val source = file.readText()
                assertTrue(source.contains("public static"))
                assertTrue(
                    source.contains("for (int i = 0; i < 10; i++)") ||
                        source.contains("System.currentTimeMillis()") ||
                        source.contains("Integer.rotateLeft") ||
                        source.contains("UUID.randomUUID()") ||
                        source.contains("throw new Exception(\"Failed\")") ||
                        source.contains("new java.util.Date()") ||
                        source.contains("System.out.println(\"Hello\")"),
                )
            }
        } finally {
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun generate_writesActivitiesWithLayoutAndManifestSnippet() {
        val outputDir = tempOutputDir()
        try {
            val result = JunkCodeGenerator.generate(
                outputDir = outputDir,
                config = JunkCodeGenerator.Config(
                    packageCount = 2,
                    classCount = 2,
                    methodsPerClass = 2,
                    activityCountPerPackage = 1,
                    namespace = "com.example.app",
                    resPrefix = "junk_",
                    seed = 7,
                    packagePrefix = "com.example.junk",
                ),
            )

            assertEquals(2, result.utilityClassCount)
            assertEquals(2, result.activityClassCount)
            assertEquals(2, result.layoutCount)
            assertEquals(2, result.activityClassNames.size)

            val activityFiles = File(outputDir, "java").walkTopDown()
                .filter { it.name.endsWith("Activity.java") }
                .toList()
            assertEquals(2, activityFiles.size)
            activityFiles.forEach { file ->
                val source = file.readText()
                assertTrue(source.contains("extends Activity"))
                assertTrue(source.contains("setContentView(R.layout."))
            }
            assertEquals(2, File(outputDir, "res/layout").listFiles()?.size)
            assertTrue(File(outputDir, "AndroidManifest.xml").readText().contains("<activity"))
        } finally {
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun generate_excludeActivityJavaFile_skipsJavaButKeepsLayoutAndManifest() {
        val outputDir = tempOutputDir()
        try {
            val result = JunkCodeGenerator.generate(
                outputDir = outputDir,
                config = JunkCodeGenerator.Config(
                    packageCount = 1,
                    classCount = 1,
                    methodsPerClass = 1,
                    activityCountPerPackage = 2,
                    excludeActivityJavaFile = true,
                    seed = 3,
                    packagePrefix = "com.example.junk",
                ),
            )

            assertEquals(0, result.activityClassCount)
            assertEquals(2, result.layoutCount)
            assertEquals(2, result.activityClassNames.size)
            assertEquals(
                0,
                File(outputDir, "java").walkTopDown().count { it.name.endsWith("Activity.java") },
            )
            assertTrue(File(outputDir, "AndroidManifest.xml").readText().contains("<activity"))
        } finally {
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun generate_isDeterministicForSameSeed() {
        val first = tempOutputDir()
        val second = tempOutputDir()
        try {
            val config = JunkCodeGenerator.Config(
                packageCount = 3,
                classCount = 6,
                methodsPerClass = 2,
                activityCountPerPackage = 0,
                seed = 99,
                packagePrefix = "com.example.junk",
            )
            JunkCodeGenerator.generate(first, config)
            JunkCodeGenerator.generate(second, config)

            val firstFiles = first.walkTopDown().filter { it.isFile }.map { it.relativeTo(first).path to it.readText() }.toMap()
            val secondFiles = second.walkTopDown().filter { it.isFile }.map { it.relativeTo(second).path to it.readText() }.toMap()
            assertEquals(firstFiles.keys, secondFiles.keys)
            firstFiles.forEach { (path, content) -> assertEquals(content, secondFiles[path]) }
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun generate_utilityJavaSourcesCompileWithJavac() {
        val outputDir = tempOutputDir()
        try {
            JunkCodeGenerator.generate(
                outputDir = outputDir,
                config = JunkCodeGenerator.Config(
                    packageCount = 5,
                    classCount = 30,
                    methodsPerClass = 8,
                    activityCountPerPackage = 0,
                    seed = 42,
                    packagePrefix = "com.example.junk",
                ),
            )
            val javaFiles = File(outputDir, "java").walkTopDown().filter { it.extension == "java" }.toList()
            val compiler = ToolProvider.getSystemJavaCompiler()
            val fileManager = compiler.getStandardFileManager(null, null, null)
            val units = fileManager.getJavaFileObjectsFromFiles(javaFiles)
            val ok = compiler.getTask(null, fileManager, null, null, null, units).call()
            assertTrue("generated junk java should compile", ok == true)
        } finally {
            outputDir.deleteRecursively()
        }
    }

    private fun tempOutputDir(): File =
        File.createTempFile("junk-gen", "").apply {
            delete()
            mkdirs()
        }
}
