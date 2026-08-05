package io.github.amsonix.molt

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MoltObfuscatePluginFunctionalTest {

    @Test
    fun flavoredApp_wiresLibraryAndKeepsVariantResourcesIsolated() {
        runFixture { root, _ ->
            val mapping = File(root, "build/shell-obfuscate/googleRelease/component-mapping.json").readText()
            assertTrue(mapping.contains("fixture.lib.LibraryActivity"))

            val generatedRes = File(root, "app/build/generated/shell-obfuscate/googleRelease/res")
            assertTrue(File(generatedRes, "layout/base.xml").isFile)
            assertTrue(File(generatedRes, "layout/google.xml").isFile)
            assertFalse(File(generatedRes, "layout/samsung.xml").exists())

            val junkKeep = File(root, "app/build/shell-obfuscate/molt-junk-keep.pro").readText()
            assertTrue(junkKeep.contains("fixture.custom.junk.**"))
        }
    }

    @Test
    fun autoDiscoverKeepXml_appliesLibraryKeepRulesDuringResourceOverlay() {
        runFixture(
            customize = { root ->
                write(
                    root,
                    "library/src/main/res/raw/keep.xml",
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources xmlns:tools="http://schemas.android.com/tools"
                        tools:keep="@layout/base" />
                    """.trimIndent(),
                )
                File(root, "app/build.gradle").writeText(
                    File(root, "app/build.gradle").readText().replace(
                        "resourceObfuscate.renameXmlFiles.set(false)",
                        "resourceObfuscate.renameXmlFiles.set(true)",
                    ),
                )
            },
        ) { root, result ->
            assertTrue(
                result.output.contains("autoDiscoverKeepXml") &&
                    result.output.contains("library/src/main/res/raw/keep.xml"),
            )

            val generatedRes = File(root, "app/build/generated/shell-obfuscate/googleRelease/res")
            assertTrue("kept layout should stay base.xml", File(generatedRes, "layout/base.xml").isFile)
            val renamedGoogle = File(generatedRes, "layout").listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.endsWith(".xml") && it != "base.xml" }
            assertTrue("google layout should be renamed", renamedGoogle.isNotEmpty())
            assertFalse(
                "google.xml should not remain when rename enabled",
                File(generatedRes, "layout/google.xml").exists(),
            )
        }
    }

    private fun runFixture(
        customize: (File) -> Unit = {},
        verify: (File, org.gradle.testkit.runner.BuildResult) -> Unit,
    ) {
        val sdk = androidSdk()
        assumeTrue("Android SDK is required for AGP TestKit", sdk.isDirectory)
        val root = Files.createTempDirectory("shell-obfuscate-testkit").toFile()
        try {
            writeFixture(root, sdk)
            customize(root)

            val result = GradleRunner.create()
                .withProjectDir(root)
                .withPluginClasspath()
                .withArguments(
                    ":app:moltObfuscatePrepareMappingGoogleRelease",
                    ":app:moltObfuscateResourcesGoogleRelease",
                    ":app:moltObfuscateGenerateJunkKeep",
                    ":app:assertCrashlyticsShellMapping",
                    "--stacktrace",
                    "--console=plain",
                )
                .build()

            verify(root, result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun assembleRelease_runsApkTransformWhenEnabled() {
        assumeTrue(
            "Set RUN_SHELL_TRANSFORM_E2E=1 to run APK transform E2E",
            transformE2eEnabled(),
        )
        val sdk = androidSdk()
        assumeTrue("Android SDK is required for AGP TestKit", sdk.isDirectory)
        val root = Files.createTempDirectory("shell-obfuscate-transform-e2e").toFile()
        try {
            writeFixture(root, sdk)
            val appGradle = File(root, "app/build.gradle")
            appGradle.writeText(
                appGradle.readText()
                    .replace("minifyEnabled true", "minifyEnabled false")
                    .replace(
                        "bundleResourceObfuscate.obfuscateApk.set(false)",
                        "bundleResourceObfuscate.obfuscateApk.set(true)",
                    )
                    + """

                android {
                    buildTypes {
                        release {
                            signingConfig signingConfigs.debug
                        }
                    }
                }

                molt {
                    componentRename.enabled.set(false)
                    viewRename.enabled.set(false)
                    allowUnsignedOutput.set(true)
                }
                """.trimIndent(),
            )
            write(
                root,
                "app/src/main/java/fixture/app/MainActivity.java",
                """
                package fixture.app;
                public class MainActivity extends android.app.Activity {}
                """.trimIndent(),
            )
            File(root, "app/src/main/AndroidManifest.xml").writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(root)
                .withPluginClasspath()
                .withArguments(
                    ":app:assembleGoogleRelease",
                    "--stacktrace",
                    "--console=plain",
                )
                .build()

            val apkDir = File(root, "app/build/outputs/apk/google/release")
            val apk = apkDir.walkTopDown().firstOrNull { it.extension == "apk" && it.isFile }
            assertTrue("APK should exist under $apkDir", apk != null)
            assertTrue(
                "APK transform task should run",
                result.task(":app:moltObfuscateTransformApkGoogleRelease")?.outcome == TaskOutcome.SUCCESS ||
                    result.output.contains("moltObfuscateTransformApkGoogleRelease"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun assembleRelease_runsApkTransformWithRenameWhenEnabled() {
        assumeTrue(
            "Set RUN_SHELL_TRANSFORM_E2E=1 to run APK transform rename E2E",
            transformE2eEnabled(),
        )
        val sdk = androidSdk()
        assumeTrue("Android SDK is required for AGP TestKit", sdk.isDirectory)
        val root = Files.createTempDirectory("shell-obfuscate-rename-e2e").toFile()
        try {
            writeFixture(root, sdk)
            val appGradle = File(root, "app/build.gradle")
            appGradle.writeText(
                appGradle.readText()
                    .replace(
                        "bundleResourceObfuscate.obfuscateApk.set(false)",
                        "bundleResourceObfuscate.obfuscateApk.set(true)",
                    )
                    + """

                android {
                    buildTypes {
                        release {
                            signingConfig signingConfigs.debug
                        }
                    }
                }

                molt {
                    allowUnsignedOutput.set(true)
                    syncBaselineProfile.set(false)
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(root)
                .withPluginClasspath()
                .withArguments(
                    ":app:assembleGoogleRelease",
                    "--stacktrace",
                    "--console=plain",
                )
                .build()

            assertTrue(
                "merge mapping task should run with rename enabled",
                result.task(":app:moltObfuscateMergeMappingGoogleRelease")?.outcome == TaskOutcome.SUCCESS,
            )
            assertTrue(
                "APK transform task should run",
                result.task(":app:moltObfuscateTransformApkGoogleRelease")?.outcome == TaskOutcome.SUCCESS,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bundleRelease_runsAabTransformWhenEnabled() {
        assumeTrue(
            "Set RUN_SHELL_TRANSFORM_E2E=1 to run AAB transform E2E",
            transformE2eEnabled(),
        )
        val sdk = androidSdk()
        assumeTrue("Android SDK is required for AGP TestKit", sdk.isDirectory)
        val root = Files.createTempDirectory("shell-obfuscate-bundle-e2e").toFile()
        try {
            writeFixture(root, sdk)
            val appGradle = File(root, "app/build.gradle")
            appGradle.writeText(
                appGradle.readText()
                    .replace("minifyEnabled true", "minifyEnabled false")
                    .replace(
                        "bundleResourceObfuscate.enabled.set(false)",
                        "bundleResourceObfuscate.enabled.set(true)",
                    )
                    + """

                android {
                    buildTypes {
                        release {
                            signingConfig signingConfigs.debug
                        }
                    }
                }

                molt {
                    componentRename.enabled.set(false)
                    viewRename.enabled.set(false)
                    allowUnsignedOutput.set(true)
                }
                """.trimIndent(),
            )
            write(
                root,
                "app/src/main/java/fixture/app/MainActivity.java",
                """
                package fixture.app;
                public class MainActivity extends android.app.Activity {}
                """.trimIndent(),
            )
            File(root, "app/src/main/AndroidManifest.xml").writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(root)
                .withPluginClasspath()
                .withArguments(
                    ":app:bundleGoogleRelease",
                    "--stacktrace",
                    "--console=plain",
                )
                .build()

            val bundleDir = File(root, "app/build/outputs/bundle/googleRelease")
            val aab = bundleDir.walkTopDown().firstOrNull { it.extension == "aab" && it.isFile }
            assertTrue("AAB should exist under $bundleDir", aab != null)
            assertTrue(
                "AAB transform task should run",
                result.task(":app:moltObfuscateTransformBundleGoogleRelease")?.outcome == TaskOutcome.SUCCESS ||
                    result.output.contains("moltObfuscateTransformBundleGoogleRelease"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeFixture(root: File, sdk: File) {
        File(root, "settings.gradle").writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'shell-obfuscate-fixture'
            include ':app', ':library'
            """.trimIndent(),
        )
        File(root, "local.properties").writeText("sdk.dir=${sdk.invariantSeparatorsPath}\n")
        File(root, "build.gradle").writeText("")
        val compileSdk = File(sdk, "platforms").listFiles()
            .orEmpty()
            .mapNotNull { it.name.removePrefix("android-").toIntOrNull() }
            .maxOrNull() ?: 35

        write(
            root,
            "app/build.gradle",
            """
            plugins {
                id 'com.android.application'
                id 'io.github.amsonix.molt'
            }

            android {
                namespace 'fixture.app'
                compileSdk $compileSdk
                defaultConfig {
                    applicationId 'fixture.app'
                    minSdk 23
                    targetSdk $compileSdk
                    versionCode 1
                    versionName '1.0'
                }
                flavorDimensions 'channel'
                productFlavors {
                    google { dimension 'channel' }
                    samsung { dimension 'channel' }
                }
                buildTypes {
                    release { minifyEnabled true }
                }
            }

            dependencies {
                implementation project(':library')
            }

            molt {
                enabledBuildTypes.set(['release'])
                seed.set(7)
                junkCode.packagePrefix.set('fixture.custom.junk')
                resourceObfuscate.renameXmlFiles.set(false)
                resourceObfuscate.injectXmlJunk.set(false)
                resourceObfuscate.imageAntiDetect.set(false)
                bundleResourceObfuscate.enabled.set(false)
                bundleResourceObfuscate.obfuscateApk.set(false)
            }

            tasks.register(
                'uploadCrashlyticsMappingFileGoogleRelease',
                com.google.firebase.crashlytics.buildtools.gradle.tasks.UploadMappingFileTask
            )
            tasks.register('assertCrashlyticsShellMapping') {
                doLast {
                    def upload = tasks.named('uploadCrashlyticsMappingFileGoogleRelease').get()
                    def mapping = upload.mergedMappingFile.get().asFile
                    assert mapping.path.endsWith('outputs/mapping/googleRelease/shell-obfuscate-mapping.txt')
                }
            }
            """.trimIndent(),
        )
        write(
            root,
            "library/build.gradle",
            """
            plugins {
                id 'com.android.library'
            }

            android {
                namespace 'fixture.lib'
                compileSdk $compileSdk
                defaultConfig { minSdk 23 }
                buildTypes {
                    release { minifyEnabled false }
                }
            }
            """.trimIndent(),
        )
        write(root, "app/src/main/AndroidManifest.xml", "<manifest />")
        write(root, "app/src/main/res/layout/base.xml", "<FrameLayout />")
        write(root, "app/src/google/res/layout/google.xml", "<FrameLayout />")
        write(root, "app/src/samsung/res/layout/samsung.xml", "<FrameLayout />")
        write(
            root,
            "library/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <activity android:name=".LibraryActivity" />
                </application>
            </manifest>
            """.trimIndent(),
        )
        write(
            root,
            "library/src/main/java/fixture/lib/LibraryActivity.java",
            """
            package fixture.lib;
            public class LibraryActivity extends android.app.Activity {}
            """.trimIndent(),
        )
    }

    private fun write(root: File, relativePath: String, content: String) {
        File(root, relativePath).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun androidSdk(): File =
        sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "${System.getProperty("user.home")}/Library/Android/sdk",
        ).firstOrNull { !it.isNullOrBlank() && File(it).isDirectory }
            ?.let(::File)
            ?: File("")

    private fun transformE2eEnabled(): Boolean =
        System.getenv("RUN_SHELL_TRANSFORM_E2E") == "1" ||
            System.getProperty("RUN_SHELL_TRANSFORM_E2E") == "1"
}
