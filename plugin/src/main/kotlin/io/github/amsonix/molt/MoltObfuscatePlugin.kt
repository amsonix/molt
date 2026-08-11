package io.github.amsonix.molt

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import io.github.amsonix.molt.internal.util.MoltObfuscateDefaults
import io.github.amsonix.molt.internal.util.SourceSetDirectoriesCompat
import io.github.amsonix.molt.internal.util.ObfuscationMappingFileResolver
import io.github.amsonix.molt.internal.util.variantCapitalizedName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File

class MoltObfuscatePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.plugins.withId("com.android.library") {
            target.logger.warn(
                "molt: skip ${target.path}; apply this plugin only on the app module " +
                    "(dependent libraries are auto-wired from the app dependency graph).",
            )
        }

        target.plugins.withId("com.android.application") {
            val extension = target.extensions.create("molt", MoltObfuscateExtension::class.java)
            target.afterEvaluate {
                io.github.amsonix.molt.internal.util.AgpToolchainCompatibility
                    .logWarnings(
                        target.logger,
                        extension.failOnAgpToolchainMismatch.get(),
                    )
            }
            configureApplication(target, extension)
        }
    }

    private fun configureApplication(project: Project, extension: MoltObfuscateExtension) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        // AGP 9 移除了旧 com.android.build.gradle.AppExtension 注册；统一走稳定新 DSL。
        val android = project.extensions.getByType(ApplicationExtension::class.java)
        wireApplicationIdDefaults(project, android, extension)
        wireJunkProguardKeep(project, android, extension)
        wireShrinkKeepValidation(project, extension, androidComponents)
        wireReleaseMinifyValidation(project, extension, android, androidComponents)

        project.tasks.register("moltPrintVariantPlan", MoltPrintVariantPlanTask::class.java) {
            androidComponents.onVariants { variant ->
                variantPlans.add(
                    MoltVariantPlanEntry(
                        variantName = variant.name,
                        applicationId = variant.applicationId.orNull ?: "",
                        buildType = variant.buildType.orEmpty(),
                        minifyEnabled = android.buildTypes.findByName(variant.buildType.orEmpty())
                            ?.isMinifyEnabled ?: false,
                    ),
                )
            }
        }

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!shouldRegister(extension, variant.buildType)) return@onVariants
            val applicationId = variant.applicationId.get()
            // fog keep 规则按精确 appId 前缀生成（见 MoltObfuscateGenerateJunkKeepTask）。
            project.tasks.named<MoltObfuscateGenerateJunkKeepTask>("moltObfuscateGenerateJunkKeep")
                .get().applicationIds.add(applicationId)
            val seed = extension.resolveSeed(variant.name, applicationId)
            val prepareTask = MoltObfuscateVariantWiring.registerPrepareMappingTask(
                project,
                extension,
                variant.name,
                seed,
            )
            val selectedSourceSets = MoltObfuscateDescriptorWiring.collectVariantSourceSets(android, variant)
            val sourceSetNames = MoltObfuscateDescriptorWiring.collectVariantSourceSetNames(variant)
            val sourceRoots = selectedSourceSets.flatMap { sourceSet ->
                SourceSetDirectoriesCompat.of(project, sourceSet, "getJava") +
                    File(project.projectDir, "src/${sourceSet.name}/kotlin")
            }.distinctBy { it.absoluteFile.normalize() }
            val manifests = selectedSourceSets.mapNotNull { sourceSet ->
                File(project.projectDir, "src/${sourceSet.name}/AndroidManifest.xml").takeIf { it.isFile }
            }
            val layoutDirs = MoltObfuscateDescriptorWiring.collectLayoutDirs(
                MoltObfuscateDescriptorWiring.collectVariantResDirs(project, android, sourceSetNames),
            )
            val descriptorTask = MoltObfuscateDescriptorWiring.registerVariantDescriptorTask(
                project = project,
                android = android,
                variantName = variant.name,
                sourceRoots = sourceRoots,
                manifests = manifests,
                layoutDirs = layoutDirs,
            )
            MoltObfuscateDescriptorWiring.addDescriptorToPrepare(
                project,
                android,
                descriptorTask,
                prepareTask,
                sourceRoots,
                manifests,
                layoutDirs,
            )
            MoltObfuscateDescriptorWiring.scheduleLibraryAutoWire(project, variant.name, prepareTask)
            val inputResDirsProvider = project.provider {
                MoltObfuscateDescriptorWiring.collectVariantResDirs(project, android, sourceSetNames)
            }
            MoltObfuscateVariantWiring.registerVariant(
                project = project,
                extension = extension,
                variant = variant,
                variantName = variant.name,
                variantApplicationId = variant.applicationId.get(),
                seed = seed,
                inputResDirsProvider = inputResDirsProvider,
                registerApplicationOutputs = {
                    val r8Mapping = ObfuscationMappingFileResolver.resolve(project, variant)
                    val mergeTask = MoltObfuscateVariantWiring.wirePrepareMapping(
                        project,
                        extension,
                        variant,
                        prepareTask,
                        r8Mapping,
                    )
                    MoltObfuscateVariantWiring.wireBundleAndApkTransforms(
                        project = project,
                        extension = extension,
                        variant = variant,
                        prepareTask = prepareTask,
                        mergeTask = mergeTask,
                        r8Mapping = r8Mapping,
                        sdkComponents = androidComponents.sdkComponents,
                        resolveProjectPackagePrefixes = { applicationId ->
                            resolveProjectPackagePrefixes(extension, applicationId)
                        },
                    )
                },
                wireGeneratedSources = { junkTask, resourceTask ->
                    variant.sources.java?.addGeneratedSourceDirectory(junkTask) { task ->
                        project.objects.directoryProperty().value(task.outputDirectory.dir("java"))
                    }
                    val junkConfig = extension.resolveJunkConfig(variant.name, variant.applicationId.get())
                    if (junkConfig.activityCountPerPackage > 0) {
                        variant.sources.res?.addGeneratedSourceDirectory(junkTask) { task ->
                            project.objects.directoryProperty().value(task.outputDirectory.dir("res"))
                        }
                    }
                    variant.sources.res?.addGeneratedSourceDirectory(
                        resourceTask,
                        MoltObfuscateResourcesTask::outputDirectory,
                    )
                },
            )
        }
    }

    private fun shouldRegister(extension: MoltObfuscateExtension, buildType: String?): Boolean {
        if (!extension.enabled.get()) return false
        if (buildType == null) return false
        return extension.enabledBuildTypes.get().contains(buildType)
    }

    private fun wireApplicationIdDefaults(
        project: Project,
        android: ApplicationExtension,
        extension: MoltObfuscateExtension,
    ) {
        val applicationIdProvider = project.provider {
            android.defaultConfig.applicationId?.takeIf { it.isNotBlank() } ?: "com.example.app"
        }
        extension.junkCode.packagePrefix.convention(
            applicationIdProvider.map(MoltObfuscateDefaults::junkPackagePrefix),
        )
        extension.projectPackagePrefixes.convention(
            applicationIdProvider.map(MoltObfuscateDefaults::projectPackagePrefixes),
        )
    }

    private fun resolveProjectPackagePrefixes(
        extension: MoltObfuscateExtension,
        applicationId: String,
    ): List<String> {
        val configured = extension.projectPackagePrefixes.get()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val resolved = if (configured.isNotEmpty()) {
            configured.map { prefix ->
                MoltObfuscateDefaults.normalizePackagePrefix(prefix.trimEnd('.'))
            }
        } else {
            MoltObfuscateDefaults.projectPackagePrefixes(applicationId)
        }
        require(resolved.isNotEmpty()) {
            "molt.projectPackagePrefixes resolved empty (applicationId=$applicationId)"
        }
        return resolved
    }

    private fun wireReleaseMinifyValidation(
        project: Project,
        extension: MoltObfuscateExtension,
        android: ApplicationExtension,
        androidComponents: ApplicationAndroidComponentsExtension,
    ) {
        androidComponents.onVariants { variant ->
            if (!extension.enabled.get()) return@onVariants
            val buildTypeName = variant.buildType.orEmpty()
            if (!shouldRegister(extension, buildTypeName)) return@onVariants
            val minifyEnabled =
                android.buildTypes.findByName(buildTypeName)?.isMinifyEnabled ?: false
            if (minifyEnabled) return@onVariants
            val settings = extension.resolveVariantSettings(variant.name)
            val needsMinify = settings.componentRenameEnabled ||
                settings.viewRenameEnabled ||
                settings.bundleResourceObfuscateEnabled ||
                settings.obfuscateApk
            if (!needsMinify) return@onVariants
            val message =
                "molt: variant '${variant.name}' has minifyEnabled=false but post-R8 features are enabled " +
                    "(componentRename=${settings.componentRenameEnabled}, " +
                    "viewRename=${settings.viewRenameEnabled}, " +
                    "bundleResourceObfuscate=${settings.bundleResourceObfuscateEnabled}, " +
                    "obfuscateApk=${settings.obfuscateApk}). " +
                    "Enable R8 on release or disable rename/arsc for this variant."
            if (extension.failOnReleaseMinifyDisabled.get()) {
                error(message)
            } else {
                project.logger.warn(message)
            }
        }
    }

    private fun wireShrinkKeepValidation(
        project: Project,
        extension: MoltObfuscateExtension,
        androidComponents: ApplicationAndroidComponentsExtension,
    ) {
        // 在 onVariants 捕获 variant 列表，afterEvaluate 再校验任务存在性
        // （旧 AppExtension.applicationVariants 在 AGP 9 已移除）。
        val variants = mutableListOf<Pair<String, String>>()
        androidComponents.onVariants { variant ->
            variants += variant.name to variant.buildType.orEmpty()
        }
        project.afterEvaluate {
            if (!extension.enabled.get() || !extension.mergeShrinkKeepXml.get()) return@afterEvaluate
            if (!extension.failOnMissingShrinkKeepTask.get()) return@afterEvaluate
            variants.forEach { (variantName, buildTypeName) ->
                if (!shouldRegister(extension, buildTypeName)) return@forEach
                val capitalized = variantCapitalizedName(variantName)
                val taskName = extension.shrinkKeepGenerateTaskName.get()
                    .replace("{Variant}", capitalized)
                check(taskName in project.tasks.names) {
                    "molt: mergeShrinkKeepXml enabled but task '$taskName' not found " +
                        "(configure shrinkKeepGenerateTaskName or set failOnMissingShrinkKeepTask=false)"
                }
            }
        }
    }

    private fun wireJunkProguardKeep(
        project: Project,
        android: ApplicationExtension,
        extension: MoltObfuscateExtension,
    ) {
        val keepTask = project.tasks.register<MoltObfuscateGenerateJunkKeepTask>(
            "moltObfuscateGenerateJunkKeep",
        ) {
            junkEnabled.set(extension.junkCode.enabled)
            packagePrefix.set(extension.junkCode.packagePrefix)
            fogEnabled.set(extension.stringEncrypt.enabled)
            fogAssetsEnabled.set(extension.assetsEncrypt.enabled)
            outputFile.set(project.layout.buildDirectory.file("shell-obfuscate/molt-junk-keep.pro"))
        }
        android.buildTypes.configureEach {
            proguardFiles(keepTask.flatMap { task -> task.outputFile })
        }
        project.tasks.matching { task ->
            task.name.startsWith("minify") && task.name.endsWith("WithR8") ||
                task.name.contains("lintVital", ignoreCase = true)
        }.configureEach {
            dependsOn(keepTask)
        }
    }

    private fun applicationIdProvider(project: Project, android: ApplicationExtension) = project.provider {
        android.defaultConfig.applicationId?.takeIf { it.isNotBlank() } ?: "com.example.app"
    }
}
