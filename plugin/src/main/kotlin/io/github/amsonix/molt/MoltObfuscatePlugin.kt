package io.github.amsonix.molt

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppExtension
import io.github.amsonix.molt.internal.util.MoltObfuscateDefaults
import io.github.amsonix.molt.internal.util.ObfuscationMappingFileResolver
import io.github.amsonix.molt.internal.util.variantCapitalizedName
import org.gradle.api.Plugin
import org.gradle.api.Project
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
            target.tasks.register("moltPrintVariantPlan", MoltPrintVariantPlanTask::class.java)
        }
    }

    private fun configureApplication(project: Project, extension: MoltObfuscateExtension) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val android = project.extensions.getByType(AppExtension::class.java)
        wireApplicationIdDefaults(project, android, extension)
        wireJunkProguardKeep(project, android, extension)
        wireShrinkKeepValidation(project, extension, android)
        wireReleaseMinifyValidation(project, extension, android)

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!shouldRegister(extension, variant.buildType)) return@onVariants
            val applicationId = variant.applicationId.get()
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
                sourceSet.java.srcDirs + File(project.projectDir, "src/${sourceSet.name}/kotlin")
            }.distinctBy { it.absoluteFile.normalize() }
            val manifests = selectedSourceSets.map { it.manifest.srcFile }
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
        android: AppExtension,
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
        android: AppExtension,
    ) {
        project.afterEvaluate {
            if (!extension.enabled.get()) return@afterEvaluate
            android.applicationVariants.forEach { variant ->
                if (!shouldRegister(extension, variant.buildType.name)) return@forEach
                if (variant.buildType.isMinifyEnabled) return@forEach
                val settings = extension.resolveVariantSettings(variant.name)
                val needsMinify = settings.componentRenameEnabled ||
                    settings.viewRenameEnabled ||
                    settings.bundleResourceObfuscateEnabled ||
                    settings.obfuscateApk
                if (!needsMinify) return@forEach
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
    }

    private fun wireShrinkKeepValidation(
        project: Project,
        extension: MoltObfuscateExtension,
        android: AppExtension,
    ) {
        project.afterEvaluate {
            if (!extension.enabled.get() || !extension.mergeShrinkKeepXml.get()) return@afterEvaluate
            if (!extension.failOnMissingShrinkKeepTask.get()) return@afterEvaluate
            android.applicationVariants.forEach { variant ->
                if (!shouldRegister(extension, variant.buildType.name)) return@forEach
                val capitalized = variantCapitalizedName(variant.name)
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
        android: AppExtension,
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

    private fun applicationIdProvider(project: Project, android: AppExtension) = project.provider {
        android.defaultConfig.applicationId?.takeIf { it.isNotBlank() } ?: "com.example.app"
    }
}
