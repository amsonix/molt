package io.github.amsonix.molt.internal.bundle

import io.github.amsonix.molt.internal.reschiper.android.JarSigner
import io.github.amsonix.molt.internal.reschiper.bundle.AppBundleAnalyzer
import io.github.amsonix.molt.internal.reschiper.bundle.AppBundlePackager
import io.github.amsonix.molt.internal.reschiper.bundle.AppBundleSigner
import io.github.amsonix.molt.internal.reschiper.obfuscation.ResourcesObfuscator
import io.github.amsonix.molt.internal.rename.RenameMapping
import io.github.amsonix.molt.internal.resource.ImagePatchRecord
import io.github.amsonix.molt.internal.resource.ZipImageEntryPatcher
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 封装 vendored ResChiper 核心，对已完成签名的 AAB 做 ResourceTable + 路径混淆并重签名。
 */
internal object BundleResourceObfuscateEngine {

    data class Config(
        val inputAab: File,
        val outputAab: File,
        val whiteList: Set<String>,
        val obfuscationMode: String = "default",
        val mappingFile: File? = null,
        val mappingOutputDir: File,
        val signing: SigningConfigSnapshot,
        val componentMapping: RenameMapping? = null,
        val viewMapping: RenameMapping? = null,
        val stringEncrypt: DexStringEncryptionConfig? = null,
        val axmlStrictMode: Boolean = false,
        val projectPackagePrefixes: List<String> = emptyList(),
        val excludeResXmlEntryPatterns: List<String> = emptyList(),
        val imageAntiDetectBundleFallback: Boolean = false,
        val imagePerceptualNoise: Boolean = false,
        val imageSeed: Int = 0,
        val metadataScope: String = "",
        val syncBaselineProfile: Boolean = false,
        val failOnBaselineProfileSyncFailure: Boolean = true,
        val baselineProfileHumanReadable: File? = null,
        val obfuscationMapping: File? = null,
        val assetsProtect: AssetsProtectionConfig? = null,
    )

    data class Result(
        val outputAab: File,
        val resourcesMappingFile: File?,
        val dexFiles: Int = 0,
        val componentManifestFiles: Int = 0,
        val viewLayoutFiles: Int = 0,
        val imagePatchRecords: List<ImagePatchRecord> = emptyList(),
    )

    fun obfuscate(config: Config): Result {
        require(config.inputAab.isFile) { "input AAB not found: ${config.inputAab.path}" }
        config.outputAab.parentFile?.mkdirs()
        if (config.outputAab.exists()) {
            config.outputAab.delete()
        }

        val bundlePath = config.inputAab.toPath()
        val outputPath = config.outputAab.toPath()
        val appBundle = AppBundleAnalyzer(bundlePath).analyze()
        ResourcesObfuscator(
            bundlePath,
            appBundle,
            config.whiteList,
            outputPath.parent,
            config.mappingFile?.toPath(),
        ).use { obfuscator ->
            obfuscator.withMode(obfuscator.getMode(config.obfuscationMode))
            val obfuscatedBundle = obfuscator.obfuscate()
            AppBundlePackager(obfuscatedBundle, outputPath).execute()
        }

        var dexFiles = 0
        var componentManifestFiles = 0
        var viewLayoutFiles = 0
        val postR8Config = ZipPostR8RenameProcessor.Config(
            componentMapping = config.componentMapping,
            viewMapping = config.viewMapping,
            axmlStrictMode = config.axmlStrictMode,
            projectPackagePrefixes = config.projectPackagePrefixes,
            excludeResXmlEntryPatterns = config.excludeResXmlEntryPatterns,
            stringEncrypt = config.stringEncrypt,
        )
        val postR8Ran = postR8Config.componentMapping != null ||
            postR8Config.viewMapping != null ||
            postR8Config.stringEncrypt != null
        if (postR8Ran) {
            val postR8Result = ZipPostR8RenameProcessor.processZipInPlace(config.outputAab, postR8Config)
            dexFiles = postR8Result.dexFiles
            componentManifestFiles = postR8Result.componentManifestFiles
            viewLayoutFiles = postR8Result.layoutFiles
        }

        MoltObfuscateBaselineProfileSync.maybeSync(
            logger = java.util.logging.Logger.getLogger(BundleResourceObfuscateEngine::class.java.name),
            zipFile = config.outputAab,
            syncEnabled = config.syncBaselineProfile,
            postR8Ran = postR8Ran,
            baselineProf = config.baselineProfileHumanReadable,
            obfuscationMapping = config.obfuscationMapping,
            failOnSyncFailure = config.failOnBaselineProfileSyncFailure,
        )

        val patchedRecords = ZipImageEntryPatcher.patchZipInPlace(
            zipFile = config.outputAab,
            seed = config.imageSeed,
            metadataScope = config.metadataScope,
            enabled = config.imageAntiDetectBundleFallback,
            perceptualNoise = config.imagePerceptualNoise,
        )
        if (patchedRecords.isNotEmpty()) {
            java.util.logging.Logger.getLogger(BundleResourceObfuscateEngine::class.java.name)
                .info("AAB image metadata fallback patched=${patchedRecords.size}")
        }

        config.assetsProtect?.let { assetsConfig ->
            AssetsProtectionEngine.patchZipInPlace(config.outputAab, assetsConfig)
        }

        if (config.signing.isComplete) {
            val signer = AppBundleSigner(outputPath)
            signer.setBundleSignature(
                JarSigner.Signature(
                    config.signing.storeFile!!.toPath(),
                    config.signing.storePassword!!,
                    config.signing.keyAlias!!,
                    config.signing.keyPassword!!,
                ),
            )
            signer.execute()
        }

        val mappingInOutputDir = File(config.outputAab.parentFile, "resources-mapping.txt")
        val mappingTarget = File(config.mappingOutputDir, "resources-mapping.txt")
        config.mappingOutputDir.mkdirs()
        val resourcesMapping = when {
            mappingInOutputDir.isFile -> {
                Files.copy(
                    mappingInOutputDir.toPath(),
                    mappingTarget.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                mappingTarget
            }
            mappingTarget.isFile -> mappingTarget
            else -> null
        }
        return Result(
            outputAab = config.outputAab,
            resourcesMappingFile = resourcesMapping,
            dexFiles = dexFiles,
            componentManifestFiles = componentManifestFiles,
            viewLayoutFiles = viewLayoutFiles,
            imagePatchRecords = patchedRecords,
        )
    }
}
