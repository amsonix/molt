package io.github.amsonix.molt.internal.keep

import com.android.aapt.Resources
import io.github.amsonix.molt.ResourceKeepResource
import io.github.amsonix.molt.ResourceKeepStaticBaseline
import java.io.File
import java.util.zip.ZipFile

/** 直接校验混淆后 AAB 的 base/resources.pb，不生成中间 APK set。 */
internal object AabResourceKeepVerifier {

    data class Result(
        val present: List<ResourceKeepResource>,
        val missing: List<ResourceKeepResource>,
    ) {
        val success: Boolean get() = missing.isEmpty()
    }

    fun verify(
        aabFile: File,
        required: List<ResourceKeepResource> = ResourceKeepStaticBaseline.artifactVerifyRequired,
    ): Result {
        require(aabFile.isFile) { "AAB not found: ${aabFile.path}" }
        if (required.isEmpty()) return Result(emptyList(), emptyList())

        val validation = ResourceTableQualifierValidator.validate(
            table = readResourceTable(aabFile),
            required = required,
        )
        return Result(present = validation.present, missing = validation.missing)
    }

    fun readResourceTable(aabFile: File): Resources.ResourceTable =
        ZipFile(aabFile).use { zip ->
            val entry = zip.getEntry(BASE_RESOURCE_TABLE_PATH)
                ?: error("AAB missing $BASE_RESOURCE_TABLE_PATH: ${aabFile.path}")
            zip.getInputStream(entry).use { input ->
                Resources.ResourceTable.parseFrom(input)
            }
        }

    private const val BASE_RESOURCE_TABLE_PATH = "base/resources.pb"
}
