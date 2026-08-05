package io.github.amsonix.molt.internal.keep

import com.android.aapt.Resources
import io.github.amsonix.molt.ResourceKeepResource
import io.github.amsonix.molt.ResourceKeepStaticBaseline

internal object MoltObfuscateArtifactVerify {

    /**
     * 合并 Firebase baseline 与 keep.xml **声明**精确条目（通配符仅白名单，不参与验包）。
     *
     * 须传入 [KeepXmlParser.parseDeclaredKeepXmlFiles] 结果，勿传入 [KeepXmlParser.mergeKeepXmlFiles]
     * （后者含静态 baseline / 通配，会扩大验包范围）。
     */
    fun resolveRequired(
        declaredKeepRules: List<KeepXmlParser.KeepResource>,
        useFirebaseBaseline: Boolean,
    ): List<ResourceKeepResource> {
        val firebase = if (useFirebaseBaseline) {
            ResourceKeepStaticBaseline.artifactVerifyRequired
        } else {
            emptyList()
        }
        val fromKeep = declaredKeepRules
            .map { rule -> rule.toResourceKeep() }
            .filter { resource -> !resource.name.endsWith("*") }
        return (firebase + fromKeep).distinctBy { resource -> resource.toQualifier() }
    }

    /**
     * 仅校验混淆**前**已存在于制品中的 keep 资源；未打包进 APK/AAB 的 SDK 条目跳过。
     */
    fun filterRequiredPresentInTable(
        table: Resources.ResourceTable,
        required: List<ResourceKeepResource>,
    ): List<ResourceKeepResource> {
        if (required.isEmpty()) return emptyList()
        val qualifiers = ResourceTableQualifierValidator.extractQualifiers(table)
        return required.filter { resource -> resource.toQualifier() in qualifiers }
    }

    fun resolveRequiredPresentInTable(
        declaredKeepRules: List<KeepXmlParser.KeepResource>,
        useFirebaseBaseline: Boolean,
        table: Resources.ResourceTable,
    ): List<ResourceKeepResource> = filterRequiredPresentInTable(
        table = table,
        required = resolveRequired(
            declaredKeepRules = declaredKeepRules,
            useFirebaseBaseline = useFirebaseBaseline,
        ),
    )
}

internal const val ARTIFACT_VERIFY_BASELINE_HINT =
    "enable useFirebaseArtifactVerifyBaseline and/or declare exact keep resources in keep.xml"
