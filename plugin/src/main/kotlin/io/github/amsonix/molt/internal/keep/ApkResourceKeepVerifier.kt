package io.github.amsonix.molt.internal.keep

import com.android.aapt.Resources
import io.github.amsonix.molt.ResourceKeepResource
import io.github.amsonix.molt.ResourceKeepStaticBaseline
import io.github.amsonix.molt.internal.bundle.Aapt2ApkConverter
import java.io.File
import java.util.zip.ZipFile

/**
 * 校验 APK resources.arsc 中关键资源名未被 shell 混淆改写。
 * 验包范围由 [MoltObfuscateArtifactVerify.resolveRequired] 决定（Firebase baseline + keep.xml 精确条目）。
 */
internal object ApkResourceKeepVerifier {

    data class Result(
        val present: List<ResourceKeepResource>,
        val missing: List<ResourceKeepResource>,
    ) {
        val success: Boolean get() = missing.isEmpty()
    }

    fun verify(
        apkFile: File,
        aapt2Executable: File,
        required: List<ResourceKeepResource> = ResourceKeepStaticBaseline.artifactVerifyRequired,
    ): Result {
        require(apkFile.isFile) { "APK not found: ${apkFile.path}" }
        require(aapt2Executable.isFile) { "aapt2 not found: ${aapt2Executable.path}" }
        if (required.isEmpty()) return Result(emptyList(), emptyList())

        val validation = ResourceTableQualifierValidator.validate(
            table = readResourceTable(apkFile, aapt2Executable),
            required = required,
        )
        return Result(present = validation.present, missing = validation.missing)
    }

    fun readResourceTable(
        apkFile: File,
        aapt2Executable: File,
    ): Resources.ResourceTable {
        val protoApk = File.createTempFile("shell-verify-", ".apk", apkFile.parentFile)
        try {
            Aapt2ApkConverter.convert(
                aapt2Executable,
                apkFile,
                protoApk,
                Aapt2ApkConverter.Format.PROTO,
            )
            ZipFile(protoApk).use { zip ->
                val entry = zip.getEntry("resources.pb")
                    ?: error("proto APK missing resources.pb: ${protoApk.path}")
                val bytes = zip.getInputStream(entry).readBytes()
                return Resources.ResourceTable.parseFrom(bytes)
            }
        } finally {
            protoApk.delete()
        }
    }
}
