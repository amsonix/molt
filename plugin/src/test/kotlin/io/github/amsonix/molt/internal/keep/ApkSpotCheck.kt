package io.github.amsonix.molt.internal.keep

import io.github.amsonix.molt.ResourceKeepStaticBaseline
import io.github.amsonix.molt.internal.bundle.AndroidBuildToolLocator
import java.io.File

/**
 * CI / 本地：对已构建 APK 做 Firebase/广告 SDK 关键资源 spot check。
 *
 * 用法：./gradlew :build-logic:molt:moltObfuscateApkSpotCheck -PspotCheckApk=/path/app.apk
 */
fun main(args: Array<String>) {
    val apkPath = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: error("Usage: ApkSpotCheck <apk-path>")
    val apk = File(apkPath)
    require(apk.isFile) { "APK not found: ${apk.absolutePath}" }

    val aapt2 = AndroidBuildToolLocator.locate("aapt2")
        ?: error("aapt2 not found under Android SDK (set ANDROID_HOME)")

    val result = ApkResourceKeepVerifier.verify(
        apkFile = apk,
        aapt2Executable = aapt2,
        required = ResourceKeepStaticBaseline.artifactVerifyRequired,
    )
    if (result.success) {
        println("spot-check OK: ${result.present.size} required resource(s) present")
        result.present.forEach { resource ->
            println("  OK ${resource.type}/${resource.name}")
        }
        return
    }
    System.err.println("spot-check FAILED: missing ${result.missing.size} resource(s)")
    result.missing.forEach { resource ->
        System.err.println("  MISSING ${resource.type}/${resource.name}")
    }
    kotlin.system.exitProcess(1)
}
