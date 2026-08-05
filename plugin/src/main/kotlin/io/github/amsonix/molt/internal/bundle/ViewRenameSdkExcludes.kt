package io.github.amsonix.molt.internal.bundle

/**
 * 三方 SDK layout 常见路径模板（post-R8 不尝试改 tag / 不验残留类名）。
 * 宿主可通过 [io.github.amsonix.molt.ViewRenameExtension.excludeResXmlEntryPatterns] 追加。
 */
internal object ViewRenameSdkExcludes {

    fun defaultResXmlPatterns(): List<String> = listOf(
        "**/res/**/tt_*.xml",
        "**/res/**/mbridge*.xml",
        "**/res/**/anythink*.xml",
        "**/res/**/gdt_*.xml",
        "**/res/**/ksad_*.xml",
        "**/res/**/applovin*.xml",
        "**/res/**/admob*.xml",
        "**/res/**/facebook*.xml",
        "**/res/**/unity*.xml",
        "**/res/**/ironsource*.xml",
        "**/res/**/vungle*.xml",
    )
}
