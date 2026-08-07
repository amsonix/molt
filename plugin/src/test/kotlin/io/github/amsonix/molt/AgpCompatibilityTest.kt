package io.github.amsonix.molt

import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** AGP 矩阵探测：smoke / transform E2E / rename E2E。 */
class AgpCompatibilityTest {

    @Test
    fun smoke_moltTasksRunAgainstConfiguredAgp() {
        val config = AgpTestFixture.configFromEnvironment()
        val sdk = AgpTestFixture.androidSdk()
        assumeTrue("Android SDK is required (AGP ${config.agpVersion})", sdk.isDirectory)

        val context = AgpTestFixture.newRunContext(config)
        try {
            AgpTestFixture.writeFixture(context, sdk)
            val result = AgpTestFixture.build(
                AgpTestFixture.createRunner(context).withArguments(
                    *AgpTestFixture.defaultProbeBuildArgs(
                        ":app:moltObfuscatePrepareMappingGoogleRelease",
                        ":app:moltObfuscateResourcesGoogleRelease",
                        ":app:moltObfuscateGenerateJunkKeep",
                        AgpTestFixture.CRASHLYTICS_ASSERT_SMOKE,
                    ),
                ),
                config,
            )

            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":app:moltObfuscatePrepareMappingGoogleRelease")?.outcome,
            )
            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":app:moltObfuscateResourcesGoogleRelease")?.outcome,
            )
            val mapping = File(context.projectDir, "build/shell-obfuscate/googleRelease/component-mapping.json")
            assertTrue("component mapping should exist", mapping.isFile)
            assertTrue(
                "mapping should include library activity",
                mapping.readText().contains("fixture.lib.LibraryActivity"),
            )
            assertTrue(
                File(context.projectDir, "app/build/shell-obfuscate/molt-junk-keep.pro").isFile,
            )
            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(AgpTestFixture.CRASHLYTICS_ASSERT_SMOKE)?.outcome,
            )
        } finally {
            AgpTestFixture.cleanup(context)
        }
    }

    @Test
    fun smoke_assembleReleaseApkTransformAgainstConfiguredAgp() {
        assumeE2eEnabled()
        runApkTransformProbe(enableRename = false)
    }

    @Test
    fun smoke_assembleReleaseApkTransformWithRenameAgainstConfiguredAgp() {
        assumeE2eEnabled()
        runApkTransformProbe(enableRename = true)
    }

    @Test
    fun smoke_bundleReleaseAabTransformAgainstConfiguredAgp() {
        assumeE2eEnabled()
        runAabTransformProbe(enableRename = false)
    }

    @Test
    fun smoke_bundleReleaseAabTransformWithRenameAgainstConfiguredAgp() {
        assumeE2eEnabled()
        runAabTransformProbe(enableRename = true)
    }

    private fun assumeE2eEnabled() {
        assumeTrue(
            "Set RUN_SHELL_TRANSFORM_E2E=1 for transform E2E probes",
            AgpTestFixture.transformE2eEnabled(),
        )
    }

    private fun runApkTransformProbe(enableRename: Boolean) {
        val config = AgpTestFixture.configFromEnvironment()
        val sdk = AgpTestFixture.androidSdk()
        assumeTrue("Android SDK is required (AGP ${config.agpVersion})", sdk.isDirectory)

        val context = AgpTestFixture.newRunContext(config)
        try {
            AgpTestFixture.writeFixture(context, sdk)
            AgpTestFixture.configureApkTransformFixture(context.projectDir, enableRename)

            val result = AgpTestFixture.build(
                AgpTestFixture.createRunner(context).withArguments(
                    *AgpTestFixture.defaultProbeBuildArgs(
                        ":app:assembleGoogleRelease",
                        *(if (enableRename) arrayOf(AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE) else emptyArray()),
                    ),
                ),
                config,
            )

            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":app:assembleGoogleRelease")?.outcome,
            )
            val apk = AgpTestFixture.findReleaseApk(context.projectDir, config.agpVersion)
            assertTrue(
                "APK missing under app/build (AGP ${config.agpVersion}); " +
                    AgpTestFixture.describeApkSearchPaths(context.projectDir, config.agpVersion),
                apk != null,
            )
            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":app:moltObfuscateTransformApkGoogleRelease")?.outcome,
            )
            if (enableRename) {
                assertEquals(
                    TaskOutcome.SUCCESS,
                    result.task(":app:moltObfuscateMergeMappingGoogleRelease")?.outcome,
                )
                val shellMapping = File(
                    context.projectDir,
                    "app/build/outputs/mapping/googleRelease/shell-obfuscate-mapping.txt",
                )
                assertTrue("merged mapping should exist after rename", shellMapping.isFile)
                assertEquals(
                    TaskOutcome.SUCCESS,
                    result.task(AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE)?.outcome,
                )
            }
        } finally {
            AgpTestFixture.cleanup(context)
        }
    }

    private fun runAabTransformProbe(enableRename: Boolean) {
        val config = AgpTestFixture.configFromEnvironment()
        val sdk = AgpTestFixture.androidSdk()
        assumeTrue("Android SDK is required (AGP ${config.agpVersion})", sdk.isDirectory)

        val context = AgpTestFixture.newRunContext(config)
        try {
            AgpTestFixture.writeFixture(context, sdk)
            AgpTestFixture.configureAabTransformFixture(context.projectDir, enableRename)

            val result = AgpTestFixture.build(
                AgpTestFixture.createRunner(context).withArguments(
                    *AgpTestFixture.defaultProbeBuildArgs(
                        ":app:bundleGoogleRelease",
                        *(if (enableRename) arrayOf(AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE) else emptyArray()),
                    ),
                ),
                config,
            )

            val aab = AgpTestFixture.findReleaseAab(context.projectDir, config.agpVersion)
            assertTrue(
                "AAB missing under app/build (AGP ${config.agpVersion}); " +
                    AgpTestFixture.describeAabSearchPaths(context.projectDir, config.agpVersion),
                aab != null,
            )
            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":app:moltObfuscateTransformBundleGoogleRelease")?.outcome,
            )
            if (enableRename) {
                assertEquals(
                    TaskOutcome.SUCCESS,
                    result.task(":app:moltObfuscateMergeMappingGoogleRelease")?.outcome,
                )
                assertEquals(
                    TaskOutcome.SUCCESS,
                    result.task(AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE)?.outcome,
                )
            }
        } finally {
            AgpTestFixture.cleanup(context)
        }
    }
}
