package io.github.amsonix.molt.internal.util

internal data class ResolvedResourceObfuscateSettings(
    val renameXmlFiles: Boolean,
    val injectXmlJunk: Boolean,
    val imageAntiDetect: Boolean,
    val imagePngMicroCompress: Boolean,
    val imageJpegMicroCompress: Boolean,
    val incrementalOverlay: Boolean,
)

/** variantConfig 覆盖全局 resourceObfuscate 细项。 */
internal object ResourceObfuscateSettingsResolver {

    fun resolve(
        globalRenameXmlFiles: Boolean,
        globalInjectXmlJunk: Boolean,
        globalImageAntiDetect: Boolean,
        globalImagePngMicroCompress: Boolean,
        globalImageJpegMicroCompress: Boolean,
        globalIncrementalOverlay: Boolean,
        variantRenameXmlFiles: Boolean? = null,
        variantInjectXmlJunk: Boolean? = null,
        variantImageAntiDetect: Boolean? = null,
        variantImagePngMicroCompress: Boolean? = null,
        variantImageJpegMicroCompress: Boolean? = null,
        variantIncrementalOverlay: Boolean? = null,
    ): ResolvedResourceObfuscateSettings = ResolvedResourceObfuscateSettings(
        renameXmlFiles = variantRenameXmlFiles ?: globalRenameXmlFiles,
        injectXmlJunk = variantInjectXmlJunk ?: globalInjectXmlJunk,
        imageAntiDetect = variantImageAntiDetect ?: globalImageAntiDetect,
        imagePngMicroCompress = variantImagePngMicroCompress ?: globalImagePngMicroCompress,
        imageJpegMicroCompress = variantImageJpegMicroCompress ?: globalImageJpegMicroCompress,
        incrementalOverlay = variantIncrementalOverlay ?: globalIncrementalOverlay,
    )
}
