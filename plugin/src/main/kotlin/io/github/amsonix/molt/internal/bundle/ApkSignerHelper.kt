package io.github.amsonix.molt.internal.bundle

import java.io.File

/** 使用 SDK apksigner（v2/v3）签名，避免 jarsigner 破坏 zipalign。 */
internal object ApkSignerHelper {

    internal const val STORE_PASSWORD_ENV = "SHELL_OBFUSCATE_STORE_PASSWORD"
    internal const val KEY_PASSWORD_ENV = "SHELL_OBFUSCATE_KEY_PASSWORD"

    fun sign(apk: File, signing: SigningConfigSnapshot) {
        sign(apk, signing, AndroidBuildToolLocator.require("apksigner"))
    }

    fun sign(
        apk: File,
        signing: SigningConfigSnapshot,
        apksigner: File,
    ) {
        require(apk.isFile) { "APK not found: ${apk.path}" }
        require(signing.isComplete) { "signing config incomplete" }
        require(apksigner.isFile) { "apksigner not found: ${apksigner.path}" }
        val storeFile = requireNotNull(signing.storeFile)
        val storePassword = requireNotNull(signing.storePassword)
        val keyAlias = requireNotNull(signing.keyAlias)
        val keyPassword = requireNotNull(signing.keyPassword)
        val command = listOf(
            apksigner.absolutePath,
            "sign",
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--ks",
            storeFile.absolutePath,
            "--ks-pass",
            "env:$STORE_PASSWORD_ENV",
            "--ks-key-alias",
            keyAlias,
            "--key-pass",
            "env:$KEY_PASSWORD_ENV",
            apk.absolutePath,
        )
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        processBuilder.environment()[STORE_PASSWORD_ENV] = storePassword
        processBuilder.environment()[KEY_PASSWORD_ENV] = keyPassword
        val process = processBuilder.start()
        val outputText = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "apksigner failed exit=$exitCode output=$outputText"
        }
    }
}
