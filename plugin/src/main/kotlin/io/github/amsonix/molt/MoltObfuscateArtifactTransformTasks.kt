package io.github.amsonix.molt

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.variant.BuiltArtifact
import io.github.amsonix.molt.internal.bundle.ApkResourceObfuscateEngine
import io.github.amsonix.molt.internal.bundle.ApkSignerHelper
import io.github.amsonix.molt.internal.bundle.ApkZipAligner
import io.github.amsonix.molt.internal.bundle.BundleResourceObfuscateEngine
import io.github.amsonix.molt.internal.bundle.KeepWhitelistConverter
import io.github.amsonix.molt.internal.bundle.SigningConfigSnapshot
import io.github.amsonix.molt.internal.bundle.MoltObfuscateBaselineProfileSync
import io.github.amsonix.molt.internal.bundle.MoltObfuscateTransformVerify
import io.github.amsonix.molt.internal.bundle.ZipPostR8RenameProcessor
import io.github.amsonix.molt.internal.keep.KeepXmlParser
import io.github.amsonix.molt.internal.mapping.R8MappingAliasExpander
import io.github.amsonix.molt.internal.rename.RenameMapping
import java.io.File

@DisableCachingByDefault(because = "Final AAB output depends on signing credentials and external build tools")
abstract class MoltObfuscateTransformBundleTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputBundle: RegularFileProperty

    @get:OutputFile
    abstract val outputBundle: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val keepXmlFiles: ConfigurableFileCollection

    @get:Input
    abstract val obfuscationMode: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val incrementalMappingFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val mappingOutputDirectory: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signingStoreFile: RegularFileProperty

    // 密码不进入 build cache 指纹
    @get:Internal
    abstract val signingStorePassword: Property<String>

    @get:Input
    abstract val signingKeyAlias: Property<String>

    @get:Internal
    abstract val signingKeyPassword: Property<String>

    @get:Input
    abstract val viewRenameEnabled: Property<Boolean>

    @get:Input
    abstract val componentRenameEnabled: Property<Boolean>

    @get:Input
    abstract val allowUnsignedOutput: Property<Boolean>

    @get:Input
    abstract val axmlStrictMode: Property<Boolean>

    @get:Input
    abstract val verifyBundleKeep: Property<Boolean>

    @get:Input
    abstract val failOnMissingBundleKeep: Property<Boolean>

    @get:Input
    abstract val useFirebaseArtifactVerifyBaseline: Property<Boolean>

    @get:Input
    abstract val failOnEmptyArtifactVerifyBaseline: Property<Boolean>

    @get:Input
    abstract val imageAntiDetectBundleFallback: Property<Boolean>

    @get:Input
    abstract val imagePerceptualNoise: Property<Boolean>

    @get:Input
    abstract val metadataScope: Property<String>

    @get:Input
    abstract val bundleImageSeed: Property<Int>

    @get:Input
    abstract val verifyBundleImageAntiDetect: Property<Boolean>

    @get:Input
    abstract val failOnBundleImageAntiDetectFailure: Property<Boolean>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imageAntiDetectReport: RegularFileProperty

    @get:Input
    abstract val excludeResXmlEntryPatterns: org.gradle.api.provider.ListProperty<String>

    @get:Input
    abstract val projectPackagePrefixes: org.gradle.api.provider.ListProperty<String>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentMappingJson: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val viewMappingJson: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val r8MappingFile: RegularFileProperty

    @get:Input
    abstract val syncBaselineProfile: Property<Boolean>

    @get:Input
    abstract val failOnBaselineProfileSyncFailure: Property<Boolean>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineProfileHumanReadable: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedObfuscationMapping: RegularFileProperty

    init {
        group = "molt"
    }

    @TaskAction
    fun transform() {
        val keepRules = KeepXmlParser.mergeKeepXmlFiles(keepXmlFiles.files)
        val declaredKeepRules = KeepXmlParser.parseDeclaredKeepXmlFiles(keepXmlFiles.files)
        val inputAab = inputBundle.get().asFile
        val whiteList = KeepWhitelistConverter.fromKeepResources(keepRules)
        val signing = resolveSigning()
        val mappingInput = incrementalMappingFiles.singleFileOrNull()
        val postR8Config = loadPostR8Config()
        BundleResourceObfuscateEngine.obfuscate(
            BundleResourceObfuscateEngine.Config(
                inputAab = inputAab,
                outputAab = outputBundle.get().asFile,
                whiteList = whiteList,
                obfuscationMode = obfuscationMode.get(),
                mappingFile = mappingInput,
                mappingOutputDir = mappingOutputDirectory.get().asFile,
                signing = signing,
                componentMapping = postR8Config.componentMapping,
                viewMapping = postR8Config.viewMapping,
                axmlStrictMode = axmlStrictMode.get(),
                projectPackagePrefixes = projectPackagePrefixes.get(),
                excludeResXmlEntryPatterns = excludeResXmlEntryPatterns.get(),
                imageAntiDetectBundleFallback = imageAntiDetectBundleFallback.get(),
                imagePerceptualNoise = imagePerceptualNoise.get(),
                imageSeed = bundleImageSeed.get(),
                metadataScope = metadataScope.get(),
                syncBaselineProfile = syncBaselineProfile.get(),
                failOnBaselineProfileSyncFailure = failOnBaselineProfileSyncFailure.get(),
                baselineProfileHumanReadable = baselineProfileHumanReadable.orNull?.asFile,
                obfuscationMapping = mergedObfuscationMapping.orNull?.asFile,
            ),
        )
        verifyBundleKeepIfEnabled(
            declaredKeepRules = declaredKeepRules,
            inputAab = inputAab,
        )
        if (verifyBundleImageAntiDetect.get()) {
            MoltObfuscateTransformVerify.verifyBundleImageAntiDetect(
                taskName = name,
                logger = logger,
                bundleFile = outputBundle.get().asFile,
                reportFile = imageAntiDetectReport.orNull?.asFile,
                fail = failOnBundleImageAntiDetectFailure.get(),
            )
        }
    }

    private fun verifyBundleKeepIfEnabled(
        declaredKeepRules: List<KeepXmlParser.KeepResource>,
        inputAab: File,
    ) {
        if (!verifyBundleKeep.get()) return
        MoltObfuscateTransformVerify.verifyBundleKeep(
            taskName = name,
            logger = logger,
            inputAab = inputAab,
            outputAab = outputBundle.get().asFile,
            declaredKeepRules = declaredKeepRules,
            useFirebaseBaseline = useFirebaseArtifactVerifyBaseline.get(),
            failOnEmptyBaseline = failOnEmptyArtifactVerifyBaseline.get(),
            failOnMissingKeep = failOnMissingBundleKeep.get(),
        )
    }

    private fun resolveSigning(): SigningConfigSnapshot {
        val signing = SigningConfigSnapshot(
            storeFile = signingStoreFile.orNull?.asFile,
            storePassword = signingStorePassword.orNull,
            keyAlias = signingKeyAlias.orNull,
            keyPassword = signingKeyPassword.orNull,
        )
        if (signing.isComplete) return signing
        check(allowUnsignedOutput.get()) {
            "${name}: signing config incomplete (storeFile/passwords/alias). " +
                "Refuse to emit unsigned AAB after shell-obfuscate transform."
        }
        logger.warn(
            "{}: AAB signing config is incomplete; allowUnsignedOutput=true, emitting an unsigned bundle.",
            name,
        )
        return signing
    }

    private fun loadPostR8Config(): ZipPostR8RenameProcessor.Config {
        val r8MappingFile = requireR8MappingFile()
        val componentMapping = if (componentRenameEnabled.get()) {
            R8MappingAliasExpander.expand(loadMappingJson(componentMappingJson), r8MappingFile)
        } else {
            null
        }
        val viewMapping = if (viewRenameEnabled.get()) {
            R8MappingAliasExpander.expand(loadMappingJson(viewMappingJson), r8MappingFile)
        } else {
            null
        }
        return ZipPostR8RenameProcessor.Config(
            componentMapping = componentMapping,
            viewMapping = viewMapping,
            axmlStrictMode = axmlStrictMode.get(),
            projectPackagePrefixes = projectPackagePrefixes.get(),
            excludeResXmlEntryPatterns = excludeResXmlEntryPatterns.get(),
        )
    }

    private fun requireR8MappingFile(): File? {
        val required = componentRenameEnabled.get() || viewRenameEnabled.get()
        if (!required) return null
        return r8MappingFile.orNull?.asFile?.takeIf { it.isFile }
            ?: error("${name}: post-R8 rename requires the R8 mapping artifact")
    }

    private fun loadMappingJson(property: RegularFileProperty): RenameMapping? {
        val jsonFile = property.orNull?.asFile?.takeIf { it.isFile } ?: return null
        val mapping = RenameMapping.fromJson(jsonFile.readText())
        return mapping.takeIf { it.entries().isNotEmpty() }
    }
}

@DisableCachingByDefault(because = "Final APK output depends on signing credentials and external build tools")
abstract class MoltObfuscateTransformApkTask : DefaultTask() {

    /** AGP SingleArtifact.APK 输入目录。 */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputApkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputApkDirectory: DirectoryProperty

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<MoltObfuscateTransformApkTask>>

    @get:Input
    abstract val seed: Property<Int>

    @get:Input
    abstract val obfuscationMode: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val keepXmlFiles: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signingStoreFile: RegularFileProperty

    @get:Internal
    abstract val signingStorePassword: Property<String>

    @get:Input
    abstract val signingKeyAlias: Property<String>

    @get:Internal
    abstract val signingKeyPassword: Property<String>

    @get:Input
    abstract val viewRenameEnabled: Property<Boolean>

    @get:Input
    abstract val componentRenameEnabled: Property<Boolean>

    @get:Input
    abstract val verifyApkKeep: Property<Boolean>

    @get:Input
    abstract val failOnMissingApkKeep: Property<Boolean>

    @get:Input
    abstract val useFirebaseArtifactVerifyBaseline: Property<Boolean>

    @get:Input
    abstract val failOnEmptyArtifactVerifyBaseline: Property<Boolean>

    @get:Input
    abstract val imageAntiDetectApkFallback: Property<Boolean>

    @get:Input
    abstract val imagePerceptualNoise: Property<Boolean>

    @get:Input
    abstract val metadataScope: Property<String>

    @get:Input
    abstract val verifyApkImageAntiDetect: Property<Boolean>

    @get:Input
    abstract val failOnApkImageAntiDetectFailure: Property<Boolean>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imageAntiDetectReport: RegularFileProperty

    @get:Input
    abstract val allowUnsignedOutput: Property<Boolean>

    @get:Input
    abstract val axmlStrictMode: Property<Boolean>

    @get:Input
    abstract val excludeResXmlEntryPatterns: org.gradle.api.provider.ListProperty<String>

    @get:Input
    abstract val projectPackagePrefixes: org.gradle.api.provider.ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aapt2Executable: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentMappingJson: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val viewMappingJson: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val r8MappingFile: RegularFileProperty

    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val incrementalMappingFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val mappingOutputDirectory: DirectoryProperty

    @get:Input
    abstract val syncBaselineProfile: Property<Boolean>

    @get:Input
    abstract val failOnBaselineProfileSyncFailure: Property<Boolean>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineProfileHumanReadable: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedObfuscationMapping: RegularFileProperty

    init {
        group = "molt"
    }

    @TaskAction
    fun transform() {
        val keepRules = KeepXmlParser.mergeKeepXmlFiles(keepXmlFiles.files)
        val declaredKeepRules = KeepXmlParser.parseDeclaredKeepXmlFiles(keepXmlFiles.files)
        val signing = resolveSigning()
        val postR8Config = loadPostR8Config()
        val postR8Ran = postR8Config.componentMapping != null || postR8Config.viewMapping != null
        val mappingInput = incrementalMappingFiles.singleFileOrNull()
        val outputDir = outputApkDirectory.get().asFile
        val inputDir = inputApkDirectory.get().asFile
        cleanStaleApkTransformArtifacts(outputDir)
        outputDir.mkdirs()
        copyApkOutputSidecars(inputDir = inputDir, outputDir = outputDir)
        syncApkOutputMetadata(inputDir = inputDir, outputDir = outputDir)
        transformationRequest.get().submit(this) { builtArtifact ->
            val inputApk = File(builtArtifact.outputFile)
            val outputApk = File(outputDir, inputApk.name)
            transformSingleApk(
                inputApk = inputApk,
                outputApk = outputApk,
                keepRules = keepRules,
                declaredKeepRules = declaredKeepRules,
                signing = signing,
                postR8Config = postR8Config,
                postR8Ran = postR8Ran,
                mappingInput = mappingInput,
            )
            outputApk
        }
    }

    private fun transformSingleApk(
        inputApk: File,
        outputApk: File,
        keepRules: List<io.github.amsonix.molt.internal.keep.KeepXmlParser.KeepResource>,
        declaredKeepRules: List<io.github.amsonix.molt.internal.keep.KeepXmlParser.KeepResource>,
        signing: SigningConfigSnapshot,
        postR8Config: ZipPostR8RenameProcessor.Config,
        postR8Ran: Boolean,
        mappingInput: File?,
    ) {
        outputApk.parentFile.mkdirs()
        val workDir = File(temporaryDir, inputApk.nameWithoutExtension).apply { mkdirs() }
        val unsignedOut = File(workDir, "unsigned.apk")
        ApkResourceObfuscateEngine.obfuscate(
            ApkResourceObfuscateEngine.Config(
                inputApk,
                unsignedOut,
                aapt2Executable.get().asFile,
                seed.get(),
                keepRules,
                obfuscationMode.get(),
                imageAntiDetectApkFallback.get(),
                imagePerceptualNoise.get(),
                metadataScope.get(),
                mappingInput,
                mappingOutputDirectory.get().asFile,
            ),
        )
        if (postR8Ran) {
            ZipPostR8RenameProcessor.processZipInPlace(unsignedOut, postR8Config)
        }
        MoltObfuscateBaselineProfileSync.maybeSync(
            logger = logger,
            zipFile = unsignedOut,
            syncEnabled = syncBaselineProfile.get(),
            postR8Ran = postR8Ran,
            baselineProf = baselineProfileHumanReadable.orNull?.asFile,
            obfuscationMapping = mergedObfuscationMapping.orNull?.asFile,
            failOnSyncFailure = failOnBaselineProfileSyncFailure.get(),
        )
        val alignedOut = File(workDir, "aligned.apk")
        ApkZipAligner.align(unsignedOut, alignedOut)
        alignedOut.copyTo(outputApk, overwrite = true)
        if (signing.isComplete) {
            ApkSignerHelper.sign(outputApk, signing)
        } else {
            logger.warn("$name: allowUnsignedOutput=true, skip APK signing")
        }
        if (verifyApkKeep.get()) {
            MoltObfuscateTransformVerify.verifyApkKeep(
                taskName = name,
                logger = logger,
                inputApk = inputApk,
                outputApk = outputApk,
                aapt2Executable = aapt2Executable.get().asFile,
                declaredKeepRules = declaredKeepRules,
                useFirebaseBaseline = useFirebaseArtifactVerifyBaseline.get(),
                failOnEmptyBaseline = failOnEmptyArtifactVerifyBaseline.get(),
                failOnMissingKeep = failOnMissingApkKeep.get(),
            )
        }
        if (verifyApkImageAntiDetect.get()) {
            MoltObfuscateTransformVerify.verifyApkImageAntiDetect(
                taskName = name,
                logger = logger,
                apkFile = outputApk,
                reportFile = imageAntiDetectReport.orNull?.asFile,
                fail = failOnApkImageAntiDetectFailure.get(),
            )
        }
        logger.lifecycle("$name: apk=$outputApk")
    }

    private fun resolveSigning(): SigningConfigSnapshot {
        val signing = SigningConfigSnapshot(
            storeFile = signingStoreFile.orNull?.asFile,
            storePassword = signingStorePassword.orNull,
            keyAlias = signingKeyAlias.orNull,
            keyPassword = signingKeyPassword.orNull,
        )
        if (signing.isComplete) return signing
        check(allowUnsignedOutput.get()) {
            "${name}: signing config incomplete (storeFile/passwords/alias). " +
                "Refuse to emit unsigned APK after shell-obfuscate transform."
        }
        return signing
    }

    private fun loadPostR8Config(): ZipPostR8RenameProcessor.Config {
        val r8MappingFile = requireR8MappingFile()
        val componentMapping = if (componentRenameEnabled.get()) {
            R8MappingAliasExpander.expand(loadMappingJson(componentMappingJson), r8MappingFile)
        } else {
            null
        }
        val viewMapping = if (viewRenameEnabled.get()) {
            R8MappingAliasExpander.expand(loadMappingJson(viewMappingJson), r8MappingFile)
        } else {
            null
        }
        return ZipPostR8RenameProcessor.Config(
            componentMapping = componentMapping,
            viewMapping = viewMapping,
            axmlStrictMode = axmlStrictMode.get(),
            projectPackagePrefixes = projectPackagePrefixes.get(),
            excludeResXmlEntryPatterns = excludeResXmlEntryPatterns.get(),
        )
    }

    private fun requireR8MappingFile(): File? {
        val required = componentRenameEnabled.get() || viewRenameEnabled.get()
        if (!required) return null
        return r8MappingFile.orNull?.asFile?.takeIf { it.isFile }
            ?: error("${name}: post-R8 rename requires the R8 mapping artifact")
    }

    private fun loadMappingJson(property: RegularFileProperty): RenameMapping? {
        val jsonFile = property.orNull?.asFile?.takeIf { it.isFile } ?: return null
        val mapping = RenameMapping.fromJson(jsonFile.readText())
        return mapping.takeIf { it.entries().isNotEmpty() }
    }
}

private fun ConfigurableFileCollection.singleFileOrNull(): File? =
    files.singleOrNull()?.takeIf { it.isFile }

private val STALE_APK_TRANSFORM_PREFIXES = listOf(
    "unsigned-",
    "aligned-",
    "mapping-rewrite-",
    "shell-post-r8-rename",
)

/** 清理历史版本误写入 outputs/apk 的临时产物，避免 AGP 误判目录状态。 */
internal fun cleanStaleApkTransformArtifacts(outputDir: File) {
    outputDir.listFiles()?.forEach { entry ->
        if (STALE_APK_TRANSFORM_PREFIXES.any { entry.name.startsWith(it) }) {
            entry.deleteRecursively()
        }
    }
}

/** 将 staging 中的最终 APK 与侧车文件发布回 package 输出目录，供 assemble 与 listing 消费。 */
internal fun publishTransformedApks(stagingDir: File, releaseDir: File) {
    releaseDir.mkdirs()
    stagingDir.listFiles()?.forEach { entry ->
        when {
            entry.isFile && entry.extension.equals("apk", ignoreCase = true) ->
                entry.copyTo(File(releaseDir, entry.name), overwrite = true)
            entry.name == "output-metadata.json" ->
                entry.copyTo(File(releaseDir, entry.name), overwrite = true)
            entry.isDirectory ->
                entry.copyRecursively(File(releaseDir, entry.name), overwrite = true)
            entry.isFile ->
                entry.copyTo(File(releaseDir, entry.name), overwrite = true)
        }
    }
}

/** 同步 package 中间产物的 output-metadata.json，供 ListingFileRedirect 指向最终 APK。 */
internal fun syncApkOutputMetadata(inputDir: File, outputDir: File) {
    val metadata = File(inputDir, "output-metadata.json")
    if (metadata.isFile) {
        metadata.copyTo(File(outputDir, "output-metadata.json"), overwrite = true)
    }
}

/** 复制 baselineProfiles 等非 APK 侧车文件，供 AGP 写 output-metadata.json。 */
internal fun copyApkOutputSidecars(inputDir: File, outputDir: File) {
    if (!inputDir.isDirectory) return
    inputDir.listFiles()?.forEach { entry ->
        when {
            entry.name == "output-metadata.json" || entry.name.endsWith(".apk") -> Unit
            entry.isDirectory -> entry.copyRecursively(File(outputDir, entry.name), overwrite = true)
            else -> entry.copyTo(File(outputDir, entry.name), overwrite = true)
        }
    }
}
