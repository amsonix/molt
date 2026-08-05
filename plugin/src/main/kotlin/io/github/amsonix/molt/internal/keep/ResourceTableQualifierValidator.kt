package io.github.amsonix.molt.internal.keep

import com.android.aapt.Resources
import io.github.amsonix.molt.ResourceKeepResource

/** APK/AAB 共用的资源表 qualifier 提取与 required 校验。 */
internal object ResourceTableQualifierValidator {

    data class Result(
        val present: List<ResourceKeepResource>,
        val missing: List<ResourceKeepResource>,
    ) {
        val success: Boolean get() = missing.isEmpty()
    }

    fun validate(
        table: Resources.ResourceTable,
        required: List<ResourceKeepResource>,
    ): Result {
        val qualifiers = extractQualifiers(table)
        val missing = required.filter { it.toQualifier() !in qualifiers }
        return Result(
            present = required.filterNot { it in missing },
            missing = missing,
        )
    }

    fun extractQualifiers(table: Resources.ResourceTable): Set<String> = buildSet {
        table.packageList.forEach { resourcePackage ->
            resourcePackage.typeList.forEach { type ->
                val typeName = type.name.ifBlank { "unknown" }
                type.entryList.forEach { entry ->
                    if (entry.name.isNotBlank()) {
                        add("$typeName/${entry.name}")
                    }
                }
            }
        }
    }
}
