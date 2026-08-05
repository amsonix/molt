package io.github.amsonix.molt.internal.bundle

import java.io.File
import java.io.IOException

/** 使用 Android Build Tools 的 aapt2 在 binary/proto APK 之间转换。 */
internal object Aapt2ApkConverter {

    enum class Format(internal val argument: String) {
        PROTO("proto"),
        BINARY("binary"),
    }

    @JvmStatic
    @Throws(IOException::class)
    fun convert(executable: File, input: File, output: File, format: Format) {
        require(executable.isFile) { "aapt2 executable not found: ${executable.path}" }
        require(executable.canExecute()) { "aapt2 is not executable: ${executable.path}" }
        require(input.isFile) { "aapt2 input APK not found: ${input.path}" }
        require(input.canonicalFile != output.canonicalFile) {
            "aapt2 input and output must differ: ${input.path}"
        }
        output.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) {
                "cannot create aapt2 output directory: ${parent.path}"
            }
        }
        if (output.exists() && !output.delete()) {
            throw IOException("cannot delete stale aapt2 output: ${output.path}")
        }

        val command = listOf(
            executable.absolutePath,
            "convert",
            "-o",
            output.absolutePath,
            "--output-format",
            format.argument,
            input.absolutePath,
        )
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IOException(
                "aapt2 convert could not start command=${command.render()} output=<unavailable>",
                e,
            )
        }
        val commandOutput = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            process.destroyForcibly()
            throw IOException(
                "aapt2 convert output read failed command=${command.render()} output=<unavailable>",
                e,
            )
        }
        val exitCode = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException(
                "aapt2 convert interrupted command=${command.render()} output=${commandOutput.display()}",
                e,
            )
        }
        if (exitCode != 0) {
            output.delete()
            throw IOException(
                "aapt2 convert failed exit=$exitCode command=${command.render()} " +
                    "output=${commandOutput.display()}",
            )
        }
        if (!output.isFile || output.length() == 0L) {
            output.delete()
            throw IOException(
                "aapt2 convert produced no output: ${output.path}; " +
                    "command=${command.render()} output=${commandOutput.display()}",
            )
        }
    }

    @JvmStatic
    fun locateExecutable(): File? = AndroidBuildToolLocator.locate("aapt2")

    private fun List<String>.render(): String = joinToString(" ") { argument ->
        if (argument.any(Char::isWhitespace)) "\"$argument\"" else argument
    }

    private fun String.display(): String = ifBlank { "<empty>" }.trim()
}
