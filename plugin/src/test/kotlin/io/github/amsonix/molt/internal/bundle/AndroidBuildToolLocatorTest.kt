package io.github.amsonix.molt.internal.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidBuildToolLocatorTest {

    @Test
    fun locate_usesEnvironmentSdkAndNewestNumericBuildToolsVersion() {
        withTempDirectory { directory ->
            val sdk = File(directory, "sdk")
            createTool(sdk, "9.0.0", "zipalign")
            val newest = createTool(sdk, "36.1.0", "zipalign")

            val result = AndroidBuildToolLocator.locate(
                toolName = "zipalign",
                environment = mapOf("ANDROID_HOME" to sdk.path),
                userHome = File(directory, "home"),
                osName = "Mac OS X",
            )

            assertEquals(newest, result)
        }
    }

    @Test
    fun locate_usesMacOsDefaultSdkWhenEnvironmentIsMissing() {
        withTempDirectory { directory ->
            val home = File(directory, "home")
            val expected = createTool(File(home, "Library/Android/sdk"), "35.0.0", "aapt2")

            val result = AndroidBuildToolLocator.locate(
                toolName = "aapt2",
                environment = emptyMap(),
                userHome = home,
                osName = "Mac OS X",
            )

            assertEquals(expected, result)
        }
    }

    @Test
    fun locate_usesWindowsExecutableNamesAndLocalAppDataSdk() {
        withTempDirectory { directory ->
            val localAppData = File(directory, "local")
            val expected = createTool(
                sdk = File(localAppData, "Android/Sdk"),
                version = "35.0.0",
                name = "apksigner.bat",
                executable = false,
            )

            val result = AndroidBuildToolLocator.locate(
                toolName = "apksigner",
                environment = mapOf("LOCALAPPDATA" to localAppData.path),
                userHome = File(directory, "home"),
                osName = "Windows 11",
            )

            assertEquals(expected, result)
        }
    }

    @Test
    fun missingToolMessage_listsConfigurationAndSearchedNames() {
        val message = AndroidBuildToolLocator.missingToolMessage(
            toolName = "zipalign",
            environment = emptyMap(),
            userHome = File("/tmp/fake-home"),
            osName = "Windows 11",
        )

        assertTrue(message.contains("ANDROID_HOME"))
        assertTrue(message.contains("ANDROID_SDK_ROOT"))
        assertTrue(message.contains("zipalign.exe"))
        assertTrue(message.contains("AppData/Local/Android/Sdk"))
    }

    private fun createTool(
        sdk: File,
        version: String,
        name: String,
        executable: Boolean = true,
    ): File = File(sdk, "build-tools/$version/$name").apply {
        parentFile.mkdirs()
        writeText("tool")
        if (executable) {
            check(setExecutable(true)) { "failed to mark tool executable: $path" }
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("android-build-tool-locator-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
