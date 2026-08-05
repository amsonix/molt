package io.github.amsonix.molt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModuleScanDescriptorTest {

    @Test
    fun relativePathsRoundTripWithoutEmbeddingModuleAbsolutePath() {
        val workspace = Files.createTempDirectory("module-scan-descriptor").toFile()
        try {
            val moduleDir = File(workspace, "feature-player").apply { mkdirs() }
            val sourceRoot = File(moduleDir, "src/main/kotlin").apply { mkdirs() }
            val manifest = File(moduleDir, "src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText("<manifest />")
            }
            val layoutDir = File(moduleDir, "src/main/res/layout").apply { mkdirs() }
            val descriptorFile = File(moduleDir, "build/shell-obfuscate/module-scan.descriptor")

            ModuleScanDescriptor(
                moduleDir = moduleDir,
                namespace = "com.example.player",
                sourceRoots = listOf(sourceRoot),
                manifestFiles = listOf(manifest),
                layoutDirs = listOf(layoutDir),
            ).writeTo(descriptorFile)

            val descriptorText = descriptorFile.readText()
            assertFalse(descriptorText.contains(moduleDir.absolutePath))

            val restored = ModuleScanDescriptor.read(descriptorFile)
            assertEquals(moduleDir.normalizedFile(), restored.moduleDir.normalizedFile())
            assertEquals("com.example.player", restored.namespace)
            assertEquals(listOf(sourceRoot.normalizedFile()), restored.sourceRoots.map { it.normalizedFile() })
            assertEquals(listOf(manifest.normalizedFile()), restored.manifestFiles.map { it.normalizedFile() })
            assertEquals(listOf(layoutDir.normalizedFile()), restored.layoutDirs.map { it.normalizedFile() })
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun File.normalizedFile(): File = absoluteFile.normalize()
}
