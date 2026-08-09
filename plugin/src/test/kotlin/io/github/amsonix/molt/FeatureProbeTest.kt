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
            FeatureProbeMatrix.ProbeType.RUNTIME -> runRuntimeProbe(row, config, sdk)
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

    private fun runRuntimeProbe(
        row: FeatureProbeMatrix.Row,
        config: AgpTestFixture.Config,
        sdk: File,
    ) {
        assumeTransformE2e(row)
        val adb = resolveAdb() ?: run {
            assumeTrue("adb not found for runtime probe (${row.featureId})", false)
            return
        }
        val deviceOnline = runCatching {
            exec(adb!!, "devices").lineSequence().any { it.endsWith("\tdevice") }
        }.getOrDefault(false)
        assumeTrue(
            "no connected Android device for runtime probe (${row.featureId})",
            deviceOnline,
        )

        val context = AgpTestFixture.newRunContext(config)
        try {
            AgpTestFixture.writeFixture(context, sdk)
            FeatureProbeProfiles.apply(context.projectDir, row.preset)
            configureTransformFixture(context.projectDir, row, enableRename = true, apk = true)
            // 产物需可安装：fixture 默认 unsigned（allowUnsignedOutput），补 debug 签名。
            val appGradle = File(context.projectDir, "app/build.gradle")
            appGradle.writeText(
                appGradle.readText().replace(
                    "release {",
                    "release {\n                        signingConfig signingConfigs.debug",
                ),
            )

            val result = AgpTestFixture.build(
                AgpTestFixture.createRunner(context).withArguments(
                    *AgpTestFixture.defaultProbeBuildArgs(
                        ":app:assembleGoogleRelease",
                        AgpTestFixture.CRASHLYTICS_ASSERT_AFTER_MERGE,
                    ),
                ),
                config,
            )
            assertApkTransformSuccess(result, context.projectDir, config, enableRename = true)
            val apk = AgpTestFixture.findReleaseApk(context.projectDir, config.agpVersion)
            assertTrue("APK missing for runtime probe", apk != null)

            exec(adb, "logcat", "-c")
            val installOut = exec(adb, "install", "-r", apk!!.absolutePath)
            assertTrue("adb install must succeed: $installOut", installOut.contains("Success"))

            val pkg = "fixture.app"
            val activity = resolveLauncherActivity(adb, pkg)
            assertTrue("launcher activity must resolve", activity != null)

            val startOut = exec(adb, "shell", "am", "start", "-W", "-n", "$pkg/$activity")
            var logcat = ""
            for (attempt in 1..15) {
                Thread.sleep(1000)
                logcat = exec(adb, "logcat", "-d")
                if (logcat.contains("MoltProbe") && logcat.contains("molt fog probe marker")) break
            }
            val crash = exec(adb, "logcat", "-d", "-b", "crash")
            val pidof = exec(adb, "shell", "pidof", pkg).trim()
            val diagnostic = buildString {
                appendLine("start=$startOut")
                appendLine("processAlive=${pidof.isNotEmpty()}")
                appendLine("crash=$crash")
                appendLine("logcatTail=")
                append(logcat.lineSequence().toList().takeLast(40).joinToString("\n"))
            }
            assertTrue(
                "Fog.decrypt must print plaintext marker at runtime (string encryption round-trip).\n$diagnostic",
                logcat.contains("molt fog probe marker"),
            )
            assertTrue("no FATAL crash on ${row.featureId}", !crash.contains("FATAL"))
            assertTrue("app process must stay alive (pidof=$pidof)", pidof.isNotEmpty())
        } finally {
            AgpTestFixture.cleanup(context)
            runCatching { exec(adb, "uninstall", "fixture.app") }
        }
    }

    private fun resolveAdb(): String? {
        val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (home != null) {
            val candidate = File(home, "platform-tools/adb")
            if (candidate.isFile) return candidate.absolutePath
        }
        val onPath = runCatching {
            ProcessBuilder("adb", "version").start().waitFor() == 0
        }.getOrDefault(false)
        return if (onPath) "adb" else null
    }

    private fun resolveLauncherActivity(adb: String, pkg: String): String? =
        exec(adb, "shell", "cmd", "package", "resolve-activity", "--brief", pkg)
            .lineSequence()
            .lastOrNull { it.contains('/') && !it.contains("=") }
            ?.substringAfter('/')

    private fun exec(adb: String, vararg args: String): String = runCatching {
        val process = ProcessBuilder(listOf(adb) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out
    }.getOrDefault("")

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
