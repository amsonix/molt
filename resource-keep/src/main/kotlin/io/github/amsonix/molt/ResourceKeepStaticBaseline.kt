package io.github.amsonix.molt

/**
 * shrink / shell 共用的精确 keep 兜底：classpath 扫描未覆盖的 SDK 固定资源名。
 */
object ResourceKeepStaticBaseline {

    val entries: List<ResourceKeepResource> = listOf(
        ResourceKeepResource("array", "firebase_performance_whitelisted_domains"),
        ResourceKeepResource("bool", "config_screen_has_notch"),
        ResourceKeepResource("bool", "config_showNavigationBar"),
        ResourceKeepResource("bool", "google_analytics_force_disable_updates"),
        ResourceKeepResource("dimen", "fringe_height"),
        ResourceKeepResource("dimen", "navigation_bar_height"),
        ResourceKeepResource("dimen", "navigation_bar_width"),
        ResourceKeepResource("dimen", "notch_h"),
        ResourceKeepResource("dimen", "notch_height"),
        ResourceKeepResource("dimen", "status_bar_height"),
        ResourceKeepResource("drawable", "alternative_network"),
        ResourceKeepResource("drawable", "btn_check_material_anim"),
        ResourceKeepResource("drawable", "invisible"),
        ResourceKeepResource("drawable", "setupview"),
        ResourceKeepResource("id", "btn_native_creative"),
        ResourceKeepResource("integer", "google_app_measurement_enable"),
        ResourceKeepResource("raw", "default"),
        ResourceKeepResource("raw", "rawresource"),
        ResourceKeepResource("string", "CronetProviderClassName"),
        ResourceKeepResource("string", "config_mainBuiltInDisplayCutout"),
        ResourceKeepResource("string", "facebook_app_id"),
        ResourceKeepResource("string", "fcm_fallback_notification_channel_label"),
        ResourceKeepResource("string", "google_app_id"),
        ResourceKeepResource("string", "project_id"),
        ResourceKeepResource("string", "gcm_defaultSenderId"),
        ResourceKeepResource("string", "google_api_key"),
        ResourceKeepResource("string", "google_storage_bucket"),
        ResourceKeepResource("string", "google_crash_reporting_api_key"),
        ResourceKeepResource("string", "default_web_client_id"),
        ResourceKeepResource("string", "firebase_database_url"),
        ResourceKeepResource("string", "com.google.firebase.crashlytics.mapping_file_id"),
        ResourceKeepResource("style", "Theme.Translucent"),
        ResourceKeepResource("xml", "appsflyer_backup_rules"),
        ResourceKeepResource("xml", "appsflyer_data_extraction_rules"),
    )

    /** 构建期 APK/AAB 校验：google-services / Firebase 运行时必读字段（须保留原名）。 */
    val artifactVerifyRequired: List<ResourceKeepResource> = listOf(
        ResourceKeepResource("string", "project_id"),
        ResourceKeepResource("string", "google_app_id"),
        ResourceKeepResource("string", "gcm_defaultSenderId"),
        ResourceKeepResource("string", "google_api_key"),
        ResourceKeepResource("string", "google_storage_bucket"),
        ResourceKeepResource("string", "com.google.firebase.crashlytics.mapping_file_id"),
    )

    /** @deprecated 使用 [artifactVerifyRequired]。 */
    @Deprecated("Use artifactVerifyRequired for APK/AAB verify")
    val apkVerifyRequired: List<ResourceKeepResource> = artifactVerifyRequired

    /** @deprecated 使用 [artifactVerifyRequired]；保留旧 API 兼容。 */
    @Deprecated("Use artifactVerifyRequired for APK/AAB verify")
    val apkVerifyExact: List<ResourceKeepResource> = artifactVerifyRequired
}
