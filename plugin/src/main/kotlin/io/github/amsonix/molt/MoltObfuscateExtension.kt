package io.github.amsonix.molt

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory

abstract class MoltObfuscateExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {

    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)

    val enabledBuildTypes: ListProperty<String> = project.objects.listProperty(String::class.java).convention(
        listOf("release"),
    )

    /** 混淆随机种子；默认由 app 的 applicationId hash 推导。 */
    val seed: Property<Int> = project.objects.property(Int::class.java)

    val keepXmlFiles: ConfigurableFileCollection = project.objects.fileCollection()

    /**
     * 自动收集 app 与各 android.library 依赖模块中的 `res/raw/keep.xml`。
     * [keepXmlFiles] 仍可追加额外文件。
     */
    val autoDiscoverKeepXml: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /** 自动合并外部 shrink keep.xml（按 [shrinkKeepRelativePath] 与 [shrinkKeepGenerateTaskName] 解析）。 */
    val mergeShrinkKeepXml: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /**
     * shrink keep 相对路径模板，`{variant}` 替换为 variant 名。
     * 默认与常见 shrink-resources 插件输出布局兼容。
     */
    val shrinkKeepRelativePath: Property<String> = project.objects.property(String::class.java)
        .convention("generated/shrink-resources/{variant}/res/raw/keep.xml")

    /**
     * 依赖的 shrink keep 生成任务名模板，`{Variant}` 为 capitalized variant 名。
     * 任务不存在时按 [failOnMissingShrinkKeepTask] 处理。
     */
    val shrinkKeepGenerateTaskName: Property<String> = project.objects.property(String::class.java)
        .convention("generateShrinkKeepXml{Variant}")

    /** APK Transform 完成后校验 keep 资源名未被 shell 混淆改写（Firebase baseline + keep.xml 精确条目）。 */
    val verifyApkKeep: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /** verifyApkKeep 发现缺失时 fail build。 */
    val failOnMissingApkKeep: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /** AAB Transform 完成后校验 keep 资源名未被 shell 混淆改写（Firebase baseline + keep.xml 精确条目）。 */
    val verifyBundleKeep: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /** verifyBundleKeep 发现缺失时 fail build。 */
    val failOnMissingBundleKeep: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /**
     * 使用内置 Firebase/google-services 验包 baseline（宿主集成 Firebase 时开启）。
     * 通用工程保持 false，由 keep.xml 自行声明关键资源。
     */
    val useFirebaseArtifactVerifyBaseline: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /** 存在 Crashlytics upload 任务时，hook 其读取合成 mapping（无插件时自动跳过）。 */
    val hookCrashlyticsMappingUpload: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /**
     * hookCrashlyticsMappingUpload 开启且 upload 任务存在但接线失败时 fail build（默认 warn）。
     */
    val failOnCrashlyticsHookFailure: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /**
     * Release variant 未开启 R8（minifyEnabled=false）但 post-R8 能力（改类名 / arsc）仍开启时 fail build（默认 warn）。
     */
    val failOnReleaseMinifyDisabled: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /**
     * verifyApkKeep/verifyBundleKeep 开启但未配置 Firebase baseline 且无 keep.xml 精确条目时 fail build。
     * 避免验包开关打开但实际跳过。
     */
    val failOnEmptyArtifactVerifyBaseline: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /**
     * mergeShrinkKeepXml 开启但 shrink keep 生成任务不存在时 fail build。
     */
    val failOnMissingShrinkKeepTask: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /** mergeJunkManifest 开启但 Manifest 合并失败时 fail build。 */
    val failOnJunkManifestMergeFailure: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /**
     * 允许 Transform 输出未签名包（仅本地调试；默认 false）。
     * 为 true 时跳过 signingConfig 校验与 v2 签名。
     */
    val allowUnsignedOutput: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /** AGP 与插件 pin 版本 major.minor 不一致时 fail build（默认 warn）。 */
    val failOnAgpToolchainMismatch: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /** 非标准 binary XML 无法替换 View FQCN 时 fail（默认 warn + skip）；仅当仍含待改类名时 fail。 */
    val axmlStrictMode: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)

    /**
     * DEX synthetic 伴生类识别的工程包前缀（须以 `.` 结尾或会自动补全）。
     * 未配置时由插件按 defaultConfig.applicationId 推导。
     */
    val projectPackagePrefixes: ListProperty<String> = project.objects.listProperty(String::class.java)

    val junkCode: JunkCodeExtension = project.objects.newInstance(JunkCodeExtension::class.java)

    /** 按 variant 覆盖 junk 配置（对齐 AndroidJunkCode variantConfig）。 */
    val variantConfig: org.gradle.api.NamedDomainObjectContainer<MoltObfuscateVariantConfig> =
        project.objects.domainObjectContainer(MoltObfuscateVariantConfig::class.java)

    val componentRename: ComponentRenameExtension =
        project.objects.newInstance(ComponentRenameExtension::class.java)

    val resourceObfuscate: ResourceObfuscateExtension =
        project.objects.newInstance(ResourceObfuscateExtension::class.java)

    val bundleResourceObfuscate: BundleResourceObfuscateExtension =
        project.objects.newInstance(BundleResourceObfuscateExtension::class.java)

    val viewRename: ViewRenameExtension =
        project.objects.newInstance(ViewRenameExtension::class.java)

    /** post-R8 DEX 改写后按 HRF + 合成 mapping 重编 baseline.prof / baseline.profm。 */
    val syncBaselineProfile: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /**
     * syncBaselineProfile 开启且 post-R8 rename 已执行时，profile **重编失败**则 fail build。
     * 缺少 baseline 源文件仅 warn+skip。
     */
    val failOnBaselineProfileSyncFailure: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /** 覆盖 baseline-prof.txt；默认 app/src/&lt;variant&gt;/generated/baselineProfiles/baseline-prof.txt */
    val baselineProfileHumanReadable: RegularFileProperty = project.objects.fileProperty()

    internal val outputRoot: DirectoryProperty =
        project.objects.directoryProperty().convention(
            project.rootProject.layout.buildDirectory.dir("shell-obfuscate"),
        )

}

abstract class JunkCodeExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    /**
     * 量级 preset：`light` / `medium` / `heavy` / `custom`。
     * `custom` 时使用下方 packageCount/classCount/methodsPerClass。
     */
    val profile: Property<String> = project.objects.property(String::class.java).convention("light")
    /** 子包数量；utility 类均分到各子包。profile=custom 时生效。 */
    val packageCount: Property<Int> = project.objects.property(Int::class.java).convention(5)
    /** utility 类总数（不含 Activity）。profile=custom 时生效。 */
    val classCount: Property<Int> = project.objects.property(Int::class.java).convention(30)
    val methodsPerClass: Property<Int> = project.objects.property(Int::class.java).convention(8)
    /**
     * 每个子包生成的 Activity 数；默认 0。
     * ponytail: 仅增加 DEX 方法数；配合 mergeJunkManifest 写入 Manifest。
     */
    val activityCountPerPackage: Property<Int> = project.objects.property(Int::class.java).convention(0)
    /**
     * 跳过 Activity .java，仍生成 layout 与 Manifest 条目（对齐 AJC excludeActivityJavaFile）。
     */
    val excludeActivityJavaFile: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)
    /** 将 junk Activity 合并进 merged manifest（需 activityCountPerPackage > 0）。 */
    val mergeJunkManifest: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)
    /** Activity layout 资源名前缀。 */
    val resPrefix: Property<String> = project.objects.property(String::class.java).convention("junk_")
    /** 未配置时由插件按 applicationId 推导为 `{applicationId}.shell.junk`。 */
    val packagePrefix: Property<String> = project.objects.property(String::class.java)
    /** 若修改 packagePrefix，插件会按 prefix 生成 -keep 规则（含子包）。 */
}

abstract class MoltObfuscateVariantConfig @Inject constructor(
    @get:org.gradle.api.tasks.Internal
    val name: String,
    objects: ObjectFactory,
) {
    /** 覆盖全局 [MoltObfuscateExtension.seed]。 */
    val seed: Property<Int> = objects.property(Int::class.java)

    val junkCode: JunkCodeVariantOverrideExtension =
        objects.newInstance(JunkCodeVariantOverrideExtension::class.java)

    val resourceObfuscate: ResourceObfuscateVariantOverrideExtension =
        objects.newInstance(ResourceObfuscateVariantOverrideExtension::class.java)

    val verify: VerifyVariantOverrideExtension =
        objects.newInstance(VerifyVariantOverrideExtension::class.java)

    val bundleResourceObfuscate: BundleResourceObfuscateVariantOverrideExtension =
        objects.newInstance(BundleResourceObfuscateVariantOverrideExtension::class.java)

    val componentRename: ComponentRenameVariantOverrideExtension =
        objects.newInstance(ComponentRenameVariantOverrideExtension::class.java)

    val viewRename: ViewRenameVariantOverrideExtension =
        objects.newInstance(ViewRenameVariantOverrideExtension::class.java)
}

/** variantConfig { create("googleRelease") { junkCode { profile.set("heavy") } } } */
abstract class JunkCodeVariantOverrideExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val profile: Property<String> = project.objects.property(String::class.java)
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java)
    val packageCount: Property<Int> = project.objects.property(Int::class.java)
    val classCount: Property<Int> = project.objects.property(Int::class.java)
    val methodsPerClass: Property<Int> = project.objects.property(Int::class.java)
    val activityCountPerPackage: Property<Int> = project.objects.property(Int::class.java)
    val excludeActivityJavaFile: Property<Boolean> = project.objects.property(Boolean::class.java)
    val mergeJunkManifest: Property<Boolean> = project.objects.property(Boolean::class.java)
    val resPrefix: Property<String> = project.objects.property(String::class.java)
}

abstract class ResourceObfuscateVariantOverrideExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java)
    val renameXmlFiles: Property<Boolean> = project.objects.property(Boolean::class.java)
    val injectXmlJunk: Property<Boolean> = project.objects.property(Boolean::class.java)
    val imageAntiDetect: Property<Boolean> = project.objects.property(Boolean::class.java)
    val imagePngMicroCompress: Property<Boolean> = project.objects.property(Boolean::class.java)
    val imageJpegMicroCompress: Property<Boolean> = project.objects.property(Boolean::class.java)
    val incrementalOverlay: Property<Boolean> = project.objects.property(Boolean::class.java)
}

abstract class VerifyVariantOverrideExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val verifyApkKeep: Property<Boolean> = project.objects.property(Boolean::class.java)
    val verifyBundleKeep: Property<Boolean> = project.objects.property(Boolean::class.java)
}

abstract class BundleResourceObfuscateVariantOverrideExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java)
    val obfuscateApk: Property<Boolean> = project.objects.property(Boolean::class.java)
}

abstract class ComponentRenameVariantOverrideExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java)
}

abstract class ViewRenameVariantOverrideExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java)
}

abstract class ComponentRenameExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)

    val excludePatterns: ListProperty<String> = project.objects.listProperty(String::class.java).convention(
        listOf("*.debug.*", "*Hilt_*", "*_HiltModules*"),
    )
}

abstract class ResourceObfuscateExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    val renameXmlFiles: Property<Boolean> = project.objects.property(Boolean::class.java).convention(false)
    /** 默认关闭；开启后在 layout 末尾注入注释占位（不影响 aapt）。 */
    val injectXmlJunk: Property<Boolean> = project.objects.property(Boolean::class.java).convention(false)
    val imageAntiDetect: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    /** master 开关；false 时 PNG/JPEG 微压缩均关闭。 */
    val imageMicroCompress: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    val imagePngMicroCompress: Property<Boolean> = project.objects.property(Boolean::class.java).convention(false)
    val imageJpegMicroCompress: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    val imageMicroCompressQuality: Property<Float> = project.objects.property(Float::class.java).convention(0.97f)
    val imageJpegMetadataMode: Property<String> = project.objects.property(String::class.java).convention("both")
    val imagePngExtraChunks: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    /** 可选 LSB 微扰动；默认关，防 pHash 场景按需开。 */
    val imagePerceptualNoise: Property<Boolean> = project.objects.property(Boolean::class.java).convention(false)
    val verifyImageAntiDetect: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    val failOnUnchangedImageAntiDetect: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)
    /** APK arsc 混淆时对 res 图片 entry 做 metadata 兜底。 */
    val imageAntiDetectApkFallback: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)
    val verifyApkImageAntiDetect: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)
    /** verifyApkImageAntiDetect 校验失败时 fail build（默认 true）。 */
    val failOnApkImageAntiDetectFailure: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)
    /** overlay 阶段 PNG/JPEG 等无法注入 metadata 时 fail build（WebP 扩展格式除外）。 */
    val failOnSkippedUnsupportedImageAntiDetect: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)
    /** AAB Transform 完成后 decode 校验全部 res 图片。 */
    val verifyBundleImageAntiDetect: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)
    val failOnBundleImageAntiDetectFailure: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)
    /** AAB Transform 阶段对 res 图片 metadata 兜底。 */
    val imageAntiDetectBundleFallback: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)
    /**
     * overlay 并行度；0 = min(4, CPU)。仅影响图片 anti-detect 与多 res 目录 cache miss。
     */
    val overlayParallelism: Property<Int> =
        project.objects.property(Int::class.java).convention(0)
    /**
     * 编译期 overlay 按 res 源目录增量：未变目录复用缓存，缩短增量构建。
     * ponytail: fingerprint 为 path+内容 hash；超大 res 目录首次扫描略慢，仍远快于全量图片处理。
     */
    val incrementalOverlay: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)

    /** WebP extended skip 占比 CI 阈值（0~1）；超过则 overlay 构建期 fail。0 = 不校验占比。 */
    val maxWebpExtendedSkipRatio: Property<Double> =
        project.objects.property(Double::class.java).convention(0.05)
}

abstract class BundleResourceObfuscateExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    /** 对最终 AAB 做 ResourceTable 混淆（官方 SingleArtifact.BUNDLE Transform）。 */
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)

    /** 对最终 APK 做 resources.arsc + res 路径混淆（SingleArtifact.APK Transform）。 */
    val obfuscateApk: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)

    /** ResChiper 模式：default / dir / file */
    val obfuscationMode: Property<String> = project.objects.property(String::class.java).convention("default")

    /** 增量复用的 resources-mapping.txt；未指定时默认读 [outputRoot]/&lt;variant&gt;/bundle-resource/resources-mapping.txt。 */
    val mappingFile: RegularFileProperty = project.objects.fileProperty()

    /** 为 true 时自动复用上次 Transform 输出的 resources-mapping（AAB 增量 arsc）。 */
    val reuseIncrementalMapping: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(true)
}

abstract class ViewRenameExtension @Inject constructor(
    private val project: org.gradle.api.Project,
) {
    /**
     * R8 之后在 APK/AAB Transform 里联动重命名 DEX 类型与 layout 二进制 tag。
     */
    val enabled: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)

    val excludePatterns: ListProperty<String> = project.objects.listProperty(String::class.java).convention(
        listOf("*.debug.*", "*Hilt_*", "*_HiltModules*"),
    )

    val excludeResXmlEntryPatterns: ListProperty<String> =
        project.objects.listProperty(String::class.java).convention(
            io.github.amsonix.molt.internal.bundle.ViewRenameSdkExcludes.defaultResXmlPatterns(),
        )
}

internal fun org.gradle.api.Project.moltExtension(): MoltObfuscateExtension =
    extensions.getByType(MoltObfuscateExtension::class.java)

internal fun MoltObfuscateExtension.resolveJunkConfig(
    variantName: String,
    applicationId: String,
): io.github.amsonix.molt.internal.junk.ResolvedJunkConfig {
    val override = variantConfig.findByName(variantName)?.junkCode
    fun <T> Property<T>.optional(): T? = if (isPresent) get() else null
    val packagePrefix = if (junkCode.packagePrefix.isPresent) {
        junkCode.packagePrefix.get()
    } else {
        io.github.amsonix.molt.internal.util.MoltObfuscateDefaults.junkPackagePrefix(applicationId)
    }
    return io.github.amsonix.molt.internal.junk.JunkConfigResolver.resolve(
        globalEnabled = junkCode.enabled.get(),
        globalProfile = junkCode.profile.get(),
        globalPackageCount = junkCode.packageCount.get(),
        globalClassCount = junkCode.classCount.get(),
        globalMethodsPerClass = junkCode.methodsPerClass.get(),
        globalActivityCountPerPackage = junkCode.activityCountPerPackage.get(),
        globalExcludeActivityJavaFile = junkCode.excludeActivityJavaFile.get(),
        globalMergeJunkManifest = junkCode.mergeJunkManifest.get(),
        globalResPrefix = junkCode.resPrefix.get(),
        globalPackagePrefix = packagePrefix,
        variantProfile = override?.profile?.optional(),
        variantEnabled = override?.enabled?.optional(),
        variantPackageCount = override?.packageCount?.optional(),
        variantClassCount = override?.classCount?.optional(),
        variantMethodsPerClass = override?.methodsPerClass?.optional(),
        variantActivityCountPerPackage = override?.activityCountPerPackage?.optional(),
        variantExcludeActivityJavaFile = override?.excludeActivityJavaFile?.optional(),
        variantMergeJunkManifest = override?.mergeJunkManifest?.optional(),
        variantResPrefix = override?.resPrefix?.optional(),
    )
}

internal fun MoltObfuscateExtension.resolveSeed(variantName: String, applicationId: String): Int {
    val variantSeed = variantConfig.findByName(variantName)?.seed
    if (variantSeed?.isPresent == true) return variantSeed.get()
    if (seed.isPresent) return seed.get()
    return applicationId.hashCode()
}

internal fun MoltObfuscateExtension.resolveResourceObfuscateSettings(
    variantName: String,
): io.github.amsonix.molt.internal.util.ResolvedResourceObfuscateSettings {
    val override = variantConfig.findByName(variantName)?.resourceObfuscate
    fun <T> Property<T>.optional(): T? = if (isPresent) get() else null
    return io.github.amsonix.molt.internal.util.ResourceObfuscateSettingsResolver.resolve(
        globalRenameXmlFiles = resourceObfuscate.renameXmlFiles.get(),
        globalInjectXmlJunk = resourceObfuscate.injectXmlJunk.get(),
        globalImageAntiDetect = resourceObfuscate.imageAntiDetect.get(),
        globalImagePngMicroCompress = resourceObfuscate.imagePngMicroCompress.get(),
        globalImageJpegMicroCompress = resourceObfuscate.imageJpegMicroCompress.get(),
        globalIncrementalOverlay = resourceObfuscate.incrementalOverlay.get(),
        variantRenameXmlFiles = override?.renameXmlFiles?.optional(),
        variantInjectXmlJunk = override?.injectXmlJunk?.optional(),
        variantImageAntiDetect = override?.imageAntiDetect?.optional(),
        variantImagePngMicroCompress = override?.imagePngMicroCompress?.optional(),
        variantImageJpegMicroCompress = override?.imageJpegMicroCompress?.optional(),
        variantIncrementalOverlay = override?.incrementalOverlay?.optional(),
    )
}

internal fun MoltObfuscateExtension.resolveVariantSettings(
    variantName: String,
): io.github.amsonix.molt.internal.util.ResolvedVariantSettings {
    val override = variantConfig.findByName(variantName)
    fun <T> Property<T>.optional(): T? = if (isPresent) get() else null
    return io.github.amsonix.molt.internal.util.VariantSettingsResolver.resolve(
        globalResourceObfuscateEnabled = resourceObfuscate.enabled.get(),
        globalVerifyApkKeep = verifyApkKeep.get(),
        globalVerifyBundleKeep = verifyBundleKeep.get(),
        globalBundleResourceObfuscateEnabled = bundleResourceObfuscate.enabled.get(),
        globalObfuscateApk = bundleResourceObfuscate.obfuscateApk.get(),
        globalComponentRenameEnabled = componentRename.enabled.get(),
        globalViewRenameEnabled = viewRename.enabled.get(),
        variantResourceObfuscateEnabled = override?.resourceObfuscate?.enabled?.optional(),
        variantVerifyApkKeep = override?.verify?.verifyApkKeep?.optional(),
        variantVerifyBundleKeep = override?.verify?.verifyBundleKeep?.optional(),
        variantBundleResourceObfuscateEnabled = override?.bundleResourceObfuscate?.enabled?.optional(),
        variantObfuscateApk = override?.bundleResourceObfuscate?.obfuscateApk?.optional(),
        variantComponentRenameEnabled = override?.componentRename?.enabled?.optional(),
        variantViewRenameEnabled = override?.viewRename?.enabled?.optional(),
    )
}
