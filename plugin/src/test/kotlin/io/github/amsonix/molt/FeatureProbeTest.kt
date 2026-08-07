package io.github.amsonix.molt

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 功能矩阵探测：读 `MOLT_FEATURE_PROBE` 选 preset + probe。 */
class FeatureProbeTest {

    @Test
    fun featureProbeRunsConfiguredRow() {
        assumeTrue(
            "Run via moltObfuscateFeatureProbeTest (-PmoltFeature=...) or set MOLT_FEATURE_PROBE",
            sequenceOf(
                System.getProperty("MOLT_FEATURE_PROBE"),
                System.getenv("MOLT_FEATURE_PROBE"),
            ).any { !it.isNullOrBlank() },
        )
        val row = FeatureProbeMatrix.resolveRow()
        assumeTrue(
            "probe=${row.probe} is shell-only; use tools/feature-probe.sh " +
                "(or :plugin:moltObfuscateSampleAssemble / :plugin:moltObfuscateIntegrationPrepare)",
            !FeatureProbeMatrix.ProbeType.isShellOnly(row.probe),
        )
        val config = AgpTestFixture.configFromEnvironment()
        val sdk = AgpTestFixture.androidSdk()
        assumeTrue(
            "Android SDK is required for feature probe ${row.featureId} (AGP ${config.agpVersion})",
            sdk.isDirectory,
        )

        when (row.probe) {
            FeatureProbeMatrix.ProbeType.SMOKE -> runSmokeProbe(row, config, sdk)
            FeatureProbeMatrix.ProbeType.APK -> runApkProbe(row, config, sdk, enableRename = false)
            FeatureProbeMatrix.ProbeType.AAB -> runAabProbe(row, config, sdk, enableRename = false)
            FeatureProbeMatrix.ProbeType.APK_RENAME -> runApkProbe(row, config, sdk, enableRename = true)
            FeatureProbeMatrix.ProbeType.AAB_RENAME -> runAabProbe(row, config, sdk, enableRename = true)
            FeatureProbeMatrix.ProbeType.ALL -> {
                runSmokeProbe(row, config, sdk)
                runApkProbe(row, config, sdk, enableRename = false)
                runAabProbe(row, config, sdk, enableRename = false)
                runApkProbe(row, config, sdk, enableRename = true)
                runAabProbe(row, config, sdk, enableRename = true)
            }
            FeatureProbeMatrix.ProbeType.SAMPLE,
            FeatureProbeMatrix.ProbeType.INTEGRATION -> error("shell-only probe; skipped above")
        }
    }

    private fun runSmokeProbe(
        row: FeatureProbeMatrix.Row,
        config: AgpTestFixture.Config,
        sdk: File,
    ) {
        val context = AgpTestFixture.newRunContext(config)
        try {
            AgpTestFixture.writeFixture(context, sdk)
            FeatureProbeProfiles.apply(context.projectDir, row.preset)

            val tasks = smokeTasksFor(row)
            val result = AgpTestFixture.build(
                AgpTestFixture.createRunner(context).withArguments(
                    *AgpTestFixture.defaultProbeBuildArgs(*tasks),
                ),
                config,
            )

            assertEquals(TaskOutcome.SUCCESS, result.task(":app:moltObfuscatePrepareMappingGoogleRelease")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":app:moltObfuscateResourcesGoogleRelease")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":app:moltObfuscateGenerateJunkKeep")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(AgpTestFixture.CRASHLYTICS_ASSERT_SMOKE)?.outcome)

            val mapping = File(context.projectDir, "build/shell-obfuscate/googleRelease/component-mapping.json")
            assertTrue("component mapping should exist", mapping.isFile)

            FeatureProbeAssertions.assertSmoke(row, context.projectDir, result.output)
        } finally {
            AgpTestFixture.cleanup(context)
        }
    }

    private fun smokeTasksFor(row: FeatureProbeMatrix.Row): Array<String> {
        val tasks = mutableListOf(
            ":app:moltObfuscatePrepareMappingGoogleRelease",
            ":app:moltObfuscateResourcesGoogleRelease",
            ":app:moltObfuscateGenerateJunkKeep",
            AgpTestFixture.CRASHLYTICS_ASSERT_SMOKE,
        )
        if (row.preset == "junk-activity") {
            tasks.add(1, ":app:moltObfuscateJunkCodeGoogleRelease")
        }
        if (row.preset == "shrink-keep") {
            tasks.add(1, ":app:generateShrinkKeepXmlGoogleRelease")
        }
        return tasks.toTypedArray()
    }

    private fun runApkProbe(
        row: FeatureProbeMatrix.Row,
        config: AgpTestFixture.Config,
        sdk: File,
        enableRename: Boolean,
    ) {
        assumeTransformE2e(row)
        val context = AgpTestFixture.newRunContext(config)
        try {
            AgpTestFixture.writeFixture(context, sdk)
            FeatureProbeProfiles.apply(context.projectDir, row.preset)
            configureTransformFixture(context.projectDir, row, enableRename, apk = true)

            val result = AgpTestFixture.build(
                AgpTestFixture.createRunner(context).withArguments(
                    *AgpTestFixture.defaultProbeBuildArgs(
                        ":app:assembleGoogleRelease",
                        *(if (enableRename) arrayOf(AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE) else emptyArray()),
                    ),
                ),
                config,
            )

            assertApkTransformSuccess(result, context.projectDir, config, enableRename)
            if (enableRename) {
                FeatureProbeAssertions.assertAfterApkRename(row, context.projectDir)
            } else {
                FeatureProbeAssertions.assertAfterApk(row, context.projectDir, config.agpVersion)
            }
        } finally {
            AgpTestFixture.cleanup(context)
        }
    }

    private fun runAabProbe(
        row: FeatureProbeMatrix.Row,
        config: AgpTestFixture.Config,
        sdk: File,
        enableRename: Boolean,
    ) {
        assumeTransformE2e(row)
        val context = AgpTestFixture.newRunContext(config)
        try {
            AgpTestFixture.writeFixture(context, sdk)
            FeatureProbeProfiles.apply(context.projectDir, row.preset)
            configureTransformFixture(context.projectDir, row, enableRename, apk = false)

            val result = AgpTestFixture.build(
                AgpTestFixture.createRunner(context).withArguments(
                    *AgpTestFixture.defaultProbeBuildArgs(
                        ":app:bundleGoogleRelease",
                        *(if (enableRename) arrayOf(AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE) else emptyArray()),
                    ),
                ),
                config,
            )

            assertAabTransformSuccess(result, context.projectDir, config, enableRename)
            FeatureProbeAssertions.assertAfterAab(row, context.projectDir, config.agpVersion)
        } finally {
            AgpTestFixture.cleanup(context)
        }
    }

    private fun configureTransformFixture(
        root: File,
        row: FeatureProbeMatrix.Row,
        enableRename: Boolean,
        apk: Boolean,
    ) {
        when {
            row.preset == "rename-full" || row.preset == "baseline-sync" ->
                if (apk) {
                    AgpTestFixture.configureApkTransformFixture(root, enableRename = true)
                } else {
                    AgpTestFixture.configureAabTransformFixture(root, enableRename = true)
                }
            row.preset == "arsc-dir" || row.preset == "arsc-file" || row.preset == "keep-verify" ->
                if (apk) {
                    AgpTestFixture.configureApkTransformFixture(root, enableRename = false)
                } else {
                    AgpTestFixture.configureAabTransformFixture(root, enableRename = false)
                }
            else ->
                if (apk) {
                    AgpTestFixture.configureApkTransformFixture(root, enableRename)
                } else {
                    AgpTestFixture.configureAabTransformFixture(root, enableRename)
                }
        }
    }

    private fun assumeTransformE2e(row: FeatureProbeMatrix.Row) {
        assumeTrue(
            "Set RUN_SHELL_TRANSFORM_E2E=1 for feature probe transform (${row.featureId})",
            AgpTestFixture.transformE2eEnabled(),
        )
    }

    private fun assertApkTransformSuccess(
        result: BuildResult,
        projectDir: File,
        config: AgpTestFixture.Config,
        enableRename: Boolean,
    ) {
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:assembleGoogleRelease")?.outcome)
        val apk = AgpTestFixture.findReleaseApk(projectDir, config.agpVersion)
        assertTrue(
            "APK missing under app/build (AGP ${config.agpVersion}); " +
                AgpTestFixture.describeApkSearchPaths(projectDir, config.agpVersion),
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
            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE)?.outcome,
            )
        }
    }

    private fun assertAabTransformSuccess(
        result: BuildResult,
        projectDir: File,
        config: AgpTestFixture.Config,
        enableRename: Boolean,
    ) {
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:bundleGoogleRelease")?.outcome)
        val aab = AgpTestFixture.findReleaseAab(projectDir, config.agpVersion)
        assertTrue(
            "AAB missing under app/build (AGP ${config.agpVersion}); " +
                AgpTestFixture.describeAabSearchPaths(projectDir, config.agpVersion),
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
    }
}
