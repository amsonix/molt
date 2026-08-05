package io.github.amsonix.molt

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MoltObfuscateGenerateJunkKeepTaskTest {

    @Test
    fun generate_usesConfiguredPackagePrefix() {
        val root = Files.createTempDirectory("junk-keep-task").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val task = project.tasks.register(
                "generateJunkKeep",
                MoltObfuscateGenerateJunkKeepTask::class.java,
            ).get()
            task.junkEnabled.set(true)
            task.packagePrefix.set("sample.custom.junk")
            task.outputFile.set(project.layout.buildDirectory.file("junk-keep.pro"))

            task.generate()

            assertTrue(task.outputFile.get().asFile.readText().contains("sample.custom.junk.**"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun generate_writesNoKeepRuleWhenJunkIsDisabled() {
        val root = Files.createTempDirectory("junk-keep-task-disabled").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val task = project.tasks.register(
                "generateJunkKeep",
                MoltObfuscateGenerateJunkKeepTask::class.java,
            ).get()
            task.junkEnabled.set(false)
            task.packagePrefix.set("sample.custom.junk")
            task.outputFile.set(project.layout.buildDirectory.file("junk-keep.pro"))

            task.generate()

            assertFalse(task.outputFile.get().asFile.readText().contains("-keep class"))
        } finally {
            root.deleteRecursively()
        }
    }
}
