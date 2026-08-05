package io.github.amsonix.molt

/** 字节码扫不到的 SDK 动态资源通配 keep。 */
object ResourceKeepBuiltinWildcards {

    val entries: List<ResourceKeepResource> = listOf(
        ResourceKeepResource("color", "tt_*"),
        ResourceKeepResource("drawable", "tt_*"),
        ResourceKeepResource("string", "tt_*"),
        ResourceKeepResource("style", "tt_*"),
        ResourceKeepResource("anim", "tt_*"),
        ResourceKeepResource("id", "tt_*"),
        ResourceKeepResource("drawable", "applovin_ic_mediation_*"),
        ResourceKeepResource("raw", "applovin_settings"),
        ResourceKeepResource("drawable", "mbridge_*"),
        ResourceKeepResource("layout", "mbridge_*"),
        ResourceKeepResource("color", "mbridge_*"),
        ResourceKeepResource("string", "mbridge_*"),
        ResourceKeepResource("dimen", "mbridge_*"),
        ResourceKeepResource("anim", "mbridge_*"),
        ResourceKeepResource("style", "mbridge_*"),
        ResourceKeepResource("raw", "mbridge_*"),
        ResourceKeepResource("drawable", "sdm_*"),
        ResourceKeepResource("layout", "sdm_*"),
        ResourceKeepResource("layout", "_*"),
    )
}
