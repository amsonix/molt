package io.github.amsonix.molt.internal.bundle

import java.io.File

/** zip 重打包后对齐 APK；native .so 使用 16KB 页对齐，兼容 16KB 设备。 */
internal object ApkZipAligner {

    fun align(input: File, output: File) {
        require(input.isFile) { "input APK not found: ${input.path}" }
        output.parentFile?.mkdirs()
        if (output.exists()) {
            output.delete()
        }
        val zipalign = AndroidBuildToolLocator.require("zipalign")
        val process = ProcessBuilder(
            zipalign.absolutePath,
            "-f",
            // -P 16: page-align uncompressed .so to 16KB; -p 是 4KB 旧语义且不能与 -P 并用
            "-P",
            "16",
            "4",
            input.absolutePath,
            output.absolutePath,
        ).redirectErrorStream(true).start()
        val outputText = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "zipalign failed exit=$exitCode output=$outputText"
        }
        require(output.isFile) { "zipalign output missing: ${output.path}" }
    }
}
