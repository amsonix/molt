package io.github.amsonix.molt

import com.android.build.gradle.AppExtension
import io.github.amsonix.molt.internal.util.variantCapitalizedName
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

/** 配置期诊断：打印各 variant 的 molt 开关与将注册的任务名。 */
abstract class MoltPrintVariantPlanTask : DefaultTask() {

    init {
        group = "molt"
        description = "Print molt feature flags and task names per variant"
    }

    @TaskAction
    fun printPlan() {
        val android = project.extensions.findByType(AppExtension::class.java)
        if (android == null) {
            logger.lifecycle("molt: Android application plugin not applied")
            return
        }
        val extension = project.moltExtension()
        if (!extension.enabled.get()) {
            logger.lifecycle("molt: enabled=false (no variants registered)")
            return
        }
        val enabledBuildTypes = extension.enabledBuildTypes.get()
        logger.lifecycle("molt variant plan for ${project.path}")
        logger.lifecycle("  enabledBuildTypes: $enabledBuildTypes")
        logger.lifecycle("  outputRoot: ${extension.outputRoot.get().asFile}")
        android.applicationVariants.forEach { variant ->
            val buildType = variant.buildType.name
            if (!enabledBuildTypes.contains(buildType)) {
                logger.lifecycle("")
                logger.lifecycle("  [skip] ${variant.name} (buildType=$buildType not in enabledBuildTypes)")
                return@forEach
            }
            printVariant(project, extension, variant.name, variant.applicationId, variant.buildType.isMinifyEnabled)
        }
    }

    private fun printVariant(
        project: Project,
        extension: MoltObfuscateExtension,
        variantName: String,
        applicationId: String,
        minifyEnabled: Boolean,
    ) {
        val capitalized = variantCapitalizedName(variantName)
        val settings = extension.resolveVariantSettings(variantName)
        val junk = extension.resolveJunkConfig(variantName, applicationId)
        val resource = extension.resolveResourceObfuscateSettings(variantName)
        logger.lifecycle("")
        logger.lifecycle("  variant: $variantName")
        logger.lifecycle("    applicationId: $applicationId")
        logger.lifecycle("    seed: ${extension.resolveSeed(variantName, applicationId)}")
        logger.lifecycle("    minifyEnabled: $minifyEnabled")
        logger.lifecycle("    junkCode: enabled=${junk.enabled} classes=${junk.classCount} packages=${junk.packageCount}")
        logger.lifecycle("    resourceObfuscate: enabled=${settings.resourceObfuscateEnabled} incrementalOverlay=${resource.incrementalOverlay}")
        logger.lifecycle("    componentRename: ${settings.componentRenameEnabled}")
        logger.lifecycle("    viewRename: ${settings.viewRenameEnabled}")
        logger.lifecycle("    bundleResourceObfuscate: enabled=${settings.bundleResourceObfuscateEnabled} obfuscateApk=${settings.obfuscateApk}")
        logger.lifecycle("    verifyApkKeep: ${settings.verifyApkKeep} verifyBundleKeep: ${settings.verifyBundleKeep}")
        logger.lifecycle("    hookCrashlyticsMappingUpload: ${extension.hookCrashlyticsMappingUpload.get()}")
        logger.lifecycle("    tasks:")
        listOf(
            "moltObfuscateWriteDescriptor$capitalized",
            "moltObfuscatePrepareMapping$capitalized",
            "moltObfuscateJunkCode$capitalized",
            "moltObfuscateResources$capitalized",
            "moltObfuscateMergeMapping$capitalized",
            "moltObfuscateTransformApk$capitalized",
            "moltObfuscateTransformBundle$capitalized",
            "uploadCrashlyticsMappingFile$capitalized (external, if Crashlytics applied)",
        ).forEach { taskName ->
            logger.lifecycle("      - $taskName")
        }
        val outputRoot = extension.outputRoot.get()
        val mergedMapping = project.layout.buildDirectory.file(
            "outputs/mapping/$variantName/shell-obfuscate-mapping.txt",
        )
        logger.lifecycle("    outputs:")
        logger.lifecycle("      - ${outputRoot.file("$variantName/component-mapping.json").asFile}")
        logger.lifecycle("      - ${mergedMapping.get().asFile}")
        logger.lifecycle("      - ${outputRoot.file("$variantName/apk-resource/resources-mapping.txt").asFile}")
        logger.lifecycle("      - ${outputRoot.file("$variantName/bundle-resource/resources-mapping.txt").asFile}")
    }
}
