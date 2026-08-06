package io.github.amsonix.molt

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.dsl.SdkComponents
import com.android.build.api.variant.ApplicationVariant
import io.github.amsonix.molt.internal.bundle.MoltObfuscateApkListingSeed
import io.github.amsonix.molt.internal.util.CrashlyticsMappingUploadWiring
import io.github.amsonix.molt.internal.bundle.VariantSigningConfig
import io.github.amsonix.molt.internal.util.Aapt2ExecutableResolver
import io.github.amsonix.molt.internal.util.AgpToolchainCompatibility
import io.github.amsonix.molt.internal.util.KeepXmlDiscovery
import io.github.amsonix.molt.internal.util.MoltObfuscateDefaults
import io.github.amsonix.molt.internal.util.requireValidObfuscationMode
import io.github.amsonix.molt.internal.util.variantCapitalizedName
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

/** variant 级任务注册：资源 overlay、APK/AAB Transform、keep 收集。 */
internal object MoltObfuscateVariantWiring {

    data class VariantTasks(
        val junkTask: TaskProvider<MoltObfuscateJunkCodeTask>,
        val resourceTask: TaskProvider<MoltObfuscateResourcesTask>,
    )

    fun registerPrepareMappingTask(
        project: Project,
        extension: MoltObfuscateExtension,
        variantName: String,
        seed: Int,
    ): TaskProvider<MoltObfuscatePrepareMappingTask> {
        val capitalized = variantCapitalizedName(variantName)
        val variantSettings = extension.resolveVariantSettings(variantName)
        return project.tasks.register(
            "moltObfuscatePrepareMapping$capitalized",
            MoltObfuscatePrepareMappingTask::class.java,
        ) {
            description = "Prepare component and view rename mapping for $variantName"
            this.variantName.set(variantName)
            this.seed.set(seed)
            excludePatterns.set(extension.componentRename.excludePatterns)
            viewRenameEnabled.set(variantSettings.viewRenameEnabled)
            viewExcludePatterns.set(extension.viewRename.excludePatterns)
            mappingJson.set(extension.outputRoot.map { dir -> dir.file("$variantName/component-mapping.json") })
            mappingReport.set(extension.outputRoot.map { dir -> dir.file("$variantName/component-mapping.txt") })
            viewMappingJson.set(extension.outputRoot.map { dir -> dir.file("$variantName/view-mapping.json") })
            viewMappingReport.set(extension.outputRoot.map { dir -> dir.file("$variantName/view-mapping.txt") })
        }
    }

    fun wirePrepareMapping(
        project: Project,
        extension: MoltObfuscateExtension,
        variant: ApplicationVariant,
        prepareTask: TaskProvider<MoltObfuscatePrepareMappingTask>,
        r8Mapping: Provider<RegularFile>,
    ): TaskProvider<MoltObfuscateMergeMappingTask> {
        val capitalized = variantCapitalizedName(variant.name)
        val variantSettings = extension.resolveVariantSettings(variant.name)
        val mergeTask = project.tasks.register<MoltObfuscateMergeMappingTask>(
            "moltObfuscateMergeMapping$capitalized",
        ) {
            description = "Merge R8 mapping with shell-obfuscate view/component renames"
            dependsOn(prepareTask)
            componentMappingJson.set(prepareTask.flatMap { prepared -> prepared.mappingJson })
            viewMappingJson.set(prepareTask.flatMap { prepared -> prepared.viewMappingJson })
            componentRenameEnabled.set(variantSettings.componentRenameEnabled)
            viewRenameEnabled.set(variantSettings.viewRenameEnabled)
            inputR8Mapping.set(r8Mapping)
            outputMapping.set(
                project.layout.buildDirectory.file(
                    "outputs/mapping/${variant.name}/shell-obfuscate-mapping.txt",
                ),
            )
        }
        mergeTask.configure {
            onlyIf {
                componentRenameEnabled.get() ||
                    viewRenameEnabled.get() ||
                    inputR8Mapping.isPresent
            }
        }
        project.tasks.matching { task ->
            task.name == "minify${capitalized}WithR8"
        }.configureEach {
            finalizedBy(mergeTask)
        }
        CrashlyticsMappingUploadWiring.wire(
            project = project,
            hookEnabled = extension.hookCrashlyticsMappingUpload.get(),
            failOnHookFailure = extension.failOnCrashlyticsHookFailure.get(),
            uploadTaskName = "uploadCrashlyticsMappingFile$capitalized",
            mergeTask = mergeTask,
        )
        return mergeTask
    }

    fun registerVariant(
        project: Project,
        extension: MoltObfuscateExtension,
        variant: ApplicationVariant,
        variantName: String,
        variantApplicationId: String,
        seed: Int,
        inputResDirsProvider: Provider<out Iterable<File>>,
        registerApplicationOutputs: (VariantTasks) -> Unit,
        wireGeneratedSources: (
            TaskProvider<MoltObfuscateJunkCodeTask>,
            TaskProvider<MoltObfuscateResourcesTask>,
        ) -> Unit,
    ) {
        if (!extension.enabled.get()) return
        val capitalized = variantCapitalizedName(variantName)
        val junkConfig = extension.resolveJunkConfig(variantName, variantApplicationId)
        val variantSettings = extension.resolveVariantSettings(variantName)

        val junkTask = project.tasks.register<MoltObfuscateJunkCodeTask>("moltObfuscateJunkCode$capitalized") {
            enabled = junkConfig.enabled
            this.seed.set(seed)
            classCount.set(junkConfig.classCount)
            packageCount.set(junkConfig.packageCount)
            methodsPerClass.set(junkConfig.methodsPerClass)
            activityCountPerPackage.set(junkConfig.activityCountPerPackage)
            excludeActivityJavaFile.set(junkConfig.excludeActivityJavaFile)
            resPrefix.set(junkConfig.resPrefix)
            namespace.set(variant.namespace.get())
            packagePrefix.set(junkConfig.packagePrefix)
            outputDirectory.set(project.layout.buildDirectory.dir("generated/shell-obfuscate/$variantName/junk"))
        }

        if (junkConfig.mergeJunkManifest && junkConfig.activityCountPerPackage > 0) {
            val mergeManifestTask =
                project.tasks.register<MoltObfuscateMergeJunkManifestTask>(
                    "moltObfuscateMergeJunkManifest$capitalized",
                ) {
                    dependsOn(junkTask)
                    failOnMergeFailure.set(extension.failOnJunkManifestMergeFailure)
                    junkManifestFile.set(junkTask.flatMap { it.outputDirectory.file("AndroidManifest.xml") })
                }
            variant.artifacts.use(mergeManifestTask)
                .wiredWithFiles(
                    { task -> task.mergedManifest },
                    { task -> task.updatedManifest },
                )
                .toTransform(SingleArtifact.MERGED_MANIFEST)
        }

        val (resourceKeepFiles, resourceShrinkTask) = wireVariantKeepFiles(
            project,
            extension,
            variantName,
            capitalized,
        )

        val resourceSettings = extension.resolveResourceObfuscateSettings(variantName)

        val resourceTask = project.tasks.register<MoltObfuscateResourcesTask>("moltObfuscateResources$capitalized") {
            enabled = variantSettings.resourceObfuscateEnabled
            resourceShrinkTask?.let { dependsOn(it) }
            this.seed.set(seed)
            applicationId.set(variantApplicationId)
            this.variantName.set(variantName)
            renameXmlFiles.set(resourceSettings.renameXmlFiles)
            injectXmlJunk.set(resourceSettings.injectXmlJunk)
            imageAntiDetect.set(resourceSettings.imageAntiDetect)
            imageMicroCompress.set(extension.resourceObfuscate.imageMicroCompress)
            imagePngMicroCompress.set(resourceSettings.imagePngMicroCompress)
            imageJpegMicroCompress.set(resourceSettings.imageJpegMicroCompress)
            imageMicroCompressQuality.set(extension.resourceObfuscate.imageMicroCompressQuality)
            imageJpegMetadataMode.set(extension.resourceObfuscate.imageJpegMetadataMode)
            imagePngExtraChunks.set(extension.resourceObfuscate.imagePngExtraChunks)
            imagePerceptualNoise.set(extension.resourceObfuscate.imagePerceptualNoise)
            verifyImageAntiDetect.set(extension.resourceObfuscate.verifyImageAntiDetect)
            failOnUnchangedImageAntiDetect.set(extension.resourceObfuscate.failOnUnchangedImageAntiDetect)
            failOnSkippedUnsupportedImageAntiDetect.set(
                extension.resourceObfuscate.failOnSkippedUnsupportedImageAntiDetect,
            )
            overlayParallelism.set(extension.resourceObfuscate.overlayParallelism)
            maxWebpExtendedSkipRatio.set(extension.resourceObfuscate.maxWebpExtendedSkipRatio)
            incrementalOverlay.set(resourceSettings.incrementalOverlay)
            keepXmlFiles.from(resourceKeepFiles)
            this.inputResDirs.from(inputResDirsProvider)
            outputDirectory.set(project.layout.buildDirectory.dir("generated/shell-obfuscate/$variantName/res"))
            overlayCacheDirectory.set(
                project.layout.buildDirectory.dir("shell-obfuscate/$variantName/res-overlay-cache"),
            )
            xmlRenameReport.set(project.layout.buildDirectory.file("shell-obfuscate/$variantName/xml-rename.txt"))
            imageAntiDetectReport.set(
                project.layout.buildDirectory.file("shell-obfuscate/$variantName/image-anti-detect-report.txt"),
            )
        }

        if (junkConfig.enabled || variantSettings.resourceObfuscateEnabled) {
            wireGeneratedSources(junkTask, resourceTask)
        }

        registerApplicationOutputs(VariantTasks(junkTask = junkTask, resourceTask = resourceTask))
    }

    fun wireBundleAndApkTransforms(
        project: Project,
        extension: MoltObfuscateExtension,
        variant: ApplicationVariant,
        prepareTask: TaskProvider<MoltObfuscatePrepareMappingTask>,
        mergeTask: TaskProvider<MoltObfuscateMergeMappingTask>,
        r8Mapping: Provider<RegularFile>,
        sdkComponents: SdkComponents,
        resolveProjectPackagePrefixes: (String) -> List<String>,
    ) {
        if (!extension.enabled.get()) return
        if (!extension.enabledBuildTypes.get().contains(variant.buildType)) return

        val capitalized = variantCapitalizedName(variant.name)
        val signing = VariantSigningConfig.fromBuildType(project, variant.buildType)
        val mappingDir = extension.outputRoot.map { dir ->
            dir.dir(variant.name).dir("bundle-resource")
        }
        val apkMappingDir = extension.outputRoot.map { dir ->
            dir.dir(variant.name).dir("apk-resource")
        }
        val defaultBaselineProf = project.layout.projectDirectory.file(
            "src/${variant.name}/generated/baselineProfiles/baseline-prof.txt",
        )
        val seedValue = extension.resolveSeed(variant.name, variant.applicationId.get())
        val variantSettings = extension.resolveVariantSettings(variant.name)

        val (variantKeepFiles, shrinkGenerateTask) = wireVariantKeepFiles(
            project,
            extension,
            variant.name,
            capitalized,
        )

        if (variantSettings.bundleResourceObfuscateEnabled) {
            val agpVersion = AgpToolchainCompatibility.readAgpVersion()
            if (agpVersion != null &&
                !AgpToolchainCompatibility.isAgpAtLeast(
                    agpVersion,
                    AgpToolchainCompatibility.MIN_AGP_FOR_BUNDLE_TRANSFORM,
                )
            ) {
                project.logger.warn(
                    "molt: AAB resource transform (bundleResourceObfuscate.enabled) is probed on " +
                        "AGP ${AgpToolchainCompatibility.MIN_AGP_FOR_BUNDLE_TRANSFORM}+; " +
                        "current=$agpVersion — bundle transform may fail",
                )
            }
            val bundleTask = project.tasks.register<MoltObfuscateTransformBundleTask>(
                "moltObfuscateTransformBundle$capitalized",
            ) {
                dependsOn(prepareTask)
                if (variantSettings.componentRenameEnabled || variantSettings.viewRenameEnabled) {
                    dependsOn(mergeTask)
                }
                shrinkGenerateTask?.let { dependsOn(it) }
                variantName.set(variant.name)
                obfuscationMode.set(
                    extension.bundleResourceObfuscate.obfuscationMode.map(::requireValidObfuscationMode),
                )
                projectPackagePrefixes.set(resolveProjectPackagePrefixes(variant.applicationId.get()))
                keepXmlFiles.from(variantKeepFiles)
                allowUnsignedOutput.set(extension.allowUnsignedOutput)
                axmlStrictMode.set(extension.axmlStrictMode)
                verifyBundleKeep.set(variantSettings.verifyBundleKeep)
                failOnMissingBundleKeep.set(extension.failOnMissingBundleKeep)
                useFirebaseArtifactVerifyBaseline.set(extension.useFirebaseArtifactVerifyBaseline)
                failOnEmptyArtifactVerifyBaseline.set(extension.failOnEmptyArtifactVerifyBaseline)
                mappingOutputDirectory.set(mappingDir)
                wireIncrementalMappingInputs(
                    project = project,
                    collection = incrementalMappingFiles,
                    extension = extension,
                    defaultMappingFile = mappingDir.map { dir -> dir.file("resources-mapping.txt") },
                )
                imageAntiDetectBundleFallback.set(extension.resourceObfuscate.imageAntiDetectBundleFallback)
                imagePerceptualNoise.set(extension.resourceObfuscate.imagePerceptualNoise)
                metadataScope.set("${variant.applicationId.get()}/${variant.name}")
                bundleImageSeed.set(seedValue)
                verifyBundleImageAntiDetect.set(extension.resourceObfuscate.verifyBundleImageAntiDetect)
                failOnBundleImageAntiDetectFailure.set(
                    extension.resourceObfuscate.failOnBundleImageAntiDetectFailure,
                )
                imageAntiDetectReport.set(
                    project.layout.buildDirectory.file(
                        "shell-obfuscate/${variant.name}/image-anti-detect-report.txt",
                    ),
                )
                excludeResXmlEntryPatterns.set(extension.viewRename.excludeResXmlEntryPatterns)
                viewRenameEnabled.set(variantSettings.viewRenameEnabled)
                viewMappingJson.set(prepareTask.flatMap { prepared -> prepared.viewMappingJson })
                componentRenameEnabled.set(variantSettings.componentRenameEnabled)
                componentMappingJson.set(prepareTask.flatMap { prepared -> prepared.mappingJson })
                r8MappingFile.set(r8Mapping)
                syncBaselineProfile.set(extension.syncBaselineProfile)
                failOnBaselineProfileSyncFailure.set(extension.failOnBaselineProfileSyncFailure)
                wireBaselineProfileHumanReadableIfPresent(
                    baselineProfileHumanReadable,
                    extension,
                    defaultBaselineProf,
                )
                if (variantSettings.componentRenameEnabled || variantSettings.viewRenameEnabled) {
                    mergedObfuscationMapping.set(mergeTask.flatMap { merged -> merged.outputMapping })
                }
                signing.storeFile?.let(signingStoreFile::set)
                signingStorePassword.set(signing.storePassword.orEmpty())
                signingKeyAlias.set(signing.keyAlias.orEmpty())
                signingKeyPassword.set(signing.keyPassword.orEmpty())
            }
            variant.artifacts.use(bundleTask)
                .wiredWithFiles(
                    MoltObfuscateTransformBundleTask::inputBundle,
                    MoltObfuscateTransformBundleTask::outputBundle,
                )
                .toTransform(SingleArtifact.BUNDLE)
        }

        if (variantSettings.obfuscateApk) {
            MoltObfuscateApkListingSeed.seedIfAbsent(project, variant)
            val apkTask = project.tasks.register<MoltObfuscateTransformApkTask>(
                "moltObfuscateTransformApk$capitalized",
            ) {
                dependsOn(prepareTask)
                if (variantSettings.componentRenameEnabled || variantSettings.viewRenameEnabled) {
                    dependsOn(mergeTask)
                }
                shrinkGenerateTask?.let { dependsOn(it) }
                seed.set(seedValue)
                obfuscationMode.set(
                    extension.bundleResourceObfuscate.obfuscationMode.map(::requireValidObfuscationMode),
                )
                projectPackagePrefixes.set(resolveProjectPackagePrefixes(variant.applicationId.get()))
                keepXmlFiles.from(variantKeepFiles)
                verifyApkKeep.set(variantSettings.verifyApkKeep)
                failOnMissingApkKeep.set(extension.failOnMissingApkKeep)
                useFirebaseArtifactVerifyBaseline.set(extension.useFirebaseArtifactVerifyBaseline)
                failOnEmptyArtifactVerifyBaseline.set(extension.failOnEmptyArtifactVerifyBaseline)
                imageAntiDetectApkFallback.set(extension.resourceObfuscate.imageAntiDetectApkFallback)
                imagePerceptualNoise.set(extension.resourceObfuscate.imagePerceptualNoise)
                metadataScope.set("${variant.applicationId.get()}/${variant.name}")
                verifyApkImageAntiDetect.set(extension.resourceObfuscate.verifyApkImageAntiDetect)
                failOnApkImageAntiDetectFailure.set(extension.resourceObfuscate.failOnApkImageAntiDetectFailure)
                imageAntiDetectReport.set(
                    project.layout.buildDirectory.file(
                        "shell-obfuscate/${variant.name}/image-anti-detect-report.txt",
                    ),
                )
                allowUnsignedOutput.set(extension.allowUnsignedOutput)
                axmlStrictMode.set(extension.axmlStrictMode)
                excludeResXmlEntryPatterns.set(extension.viewRename.excludeResXmlEntryPatterns)
                viewRenameEnabled.set(variantSettings.viewRenameEnabled)
                viewMappingJson.set(prepareTask.flatMap { prepared -> prepared.viewMappingJson })
                componentRenameEnabled.set(variantSettings.componentRenameEnabled)
                componentMappingJson.set(prepareTask.flatMap { prepared -> prepared.mappingJson })
                r8MappingFile.set(r8Mapping)
                syncBaselineProfile.set(extension.syncBaselineProfile)
                failOnBaselineProfileSyncFailure.set(extension.failOnBaselineProfileSyncFailure)
                wireBaselineProfileHumanReadableIfPresent(
                    baselineProfileHumanReadable,
                    extension,
                    defaultBaselineProf,
                )
                if (variantSettings.componentRenameEnabled || variantSettings.viewRenameEnabled) {
                    mergedObfuscationMapping.set(mergeTask.flatMap { merged -> merged.outputMapping })
                }
                wireIncrementalMappingInputs(
                    project = project,
                    collection = incrementalMappingFiles,
                    extension = extension,
                    defaultMappingFile = apkMappingDir.map { dir -> dir.file("resources-mapping.txt") },
                )
                mappingOutputDirectory.set(apkMappingDir)
                aapt2Executable.set(Aapt2ExecutableResolver.resolve(project, sdkComponents))
                signing.storeFile?.let(signingStoreFile::set)
                signingStorePassword.set(signing.storePassword.orEmpty())
                signingKeyAlias.set(signing.keyAlias.orEmpty())
                signingKeyPassword.set(signing.keyPassword.orEmpty())
            }
            val apkTransformRequest = variant.artifacts.use(apkTask)
                .wiredWithDirectories(
                    MoltObfuscateTransformApkTask::inputApkDirectory,
                    MoltObfuscateTransformApkTask::outputApkDirectory,
                )
                .toTransformMany(SingleArtifact.APK)
            apkTask.configure {
                transformationRequest.set(apkTransformRequest)
            }
        }
    }

    fun wireVariantKeepFiles(
        project: Project,
        extension: MoltObfuscateExtension,
        variantName: String,
        capitalized: String,
    ): Pair<ConfigurableFileCollection, String?> {
        val keepFiles = project.objects.fileCollection()
        if (extension.autoDiscoverKeepXml.get()) {
            keepFiles.from(
                project.provider {
                    val discovered = KeepXmlDiscovery.discoverForVariant(project, variantName)
                    logDiscoveredKeepXml(project, variantName, discovered)
                    discovered
                },
            )
        }
        keepFiles.from(extension.keepXmlFiles)
        val shrinkGenerateTask = wireVariantShrinkKeepFiles(
            project,
            extension,
            variantName,
            capitalized,
            keepFiles,
        )
        return keepFiles to shrinkGenerateTask
    }

    private fun logDiscoveredKeepXml(project: Project, variantName: String, discovered: List<File>) {
        if (discovered.isEmpty()) {
            project.logger.lifecycle(
                "molt: autoDiscoverKeepXml: no keep.xml for variant=$variantName",
            )
            return
        }
        val paths = discovered.joinToString { file ->
            runCatching { file.relativeTo(project.rootProject.projectDir).path }
                .getOrElse { file.path }
        }
        project.logger.lifecycle(
            "molt: autoDiscoverKeepXml: ${discovered.size} file(s) for variant=$variantName: $paths",
        )
    }

    /** @return shrink generate task name when merge enabled and task exists, else null */
    private fun wireVariantShrinkKeepFiles(
        project: Project,
        extension: MoltObfuscateExtension,
        variantName: String,
        capitalized: String,
        keepFiles: ConfigurableFileCollection,
    ): String? {
        if (!extension.mergeShrinkKeepXml.get()) return null
        val keepRelativePath = MoltObfuscateDefaults.shrinkKeepRelativePath(
            variantName = variantName,
            pattern = extension.shrinkKeepRelativePath.get(),
        )
        keepFiles.from(project.layout.buildDirectory.file(keepRelativePath))
        val taskName = extension.shrinkKeepGenerateTaskName.get()
            .replace("{Variant}", capitalized)
        if (taskName !in project.tasks.names) {
            val message =
                "molt: mergeShrinkKeepXml enabled but task '$taskName' not found " +
                    "(will fail at end of configuration if failOnMissingShrinkKeepTask=true)"
            project.logger.warn(message)
            return null
        }
        return taskName
    }

    /** 首次构建时 incremental mapping 尚不存在；空 collection 合法，TaskAction 用 singleFileOrNull() 处理。 */
    fun wireIncrementalMappingInputs(
        project: Project,
        collection: ConfigurableFileCollection,
        extension: MoltObfuscateExtension,
        defaultMappingFile: Provider<RegularFile>,
    ) {
        if (extension.bundleResourceObfuscate.reuseIncrementalMapping.get()) {
            val mappingProvider = extension.bundleResourceObfuscate.mappingFile.orElse(defaultMappingFile)
            collection.from(
                project.provider {
                    val file = mappingProvider.orNull?.asFile
                    if (file != null && file.isFile) project.files(file) else project.files()
                },
            )
        } else if (extension.bundleResourceObfuscate.mappingFile.isPresent) {
            collection.from(extension.bundleResourceObfuscate.mappingFile)
        }
    }

    /**
     * @Optional @InputFile 在路径不存在时仍会触发 Gradle 校验失败；
     * 运行时 missing baseline-prof 已 warn+skip，此处仅在文件存在（或用户显式配置）时接线。
     */
    private fun wireBaselineProfileHumanReadableIfPresent(
        target: RegularFileProperty,
        extension: MoltObfuscateExtension,
        defaultBaselineProf: RegularFile,
    ) {
        if (extension.baselineProfileHumanReadable.isPresent) {
            target.set(extension.baselineProfileHumanReadable)
        } else if (defaultBaselineProf.asFile.isFile) {
            target.set(defaultBaselineProf)
        }
    }
}
