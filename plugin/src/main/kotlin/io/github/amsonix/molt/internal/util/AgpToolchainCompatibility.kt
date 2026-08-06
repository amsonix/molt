package io.github.amsonix.molt.internal.util

import org.gradle.api.logging.Logger

/** 插件 pin 版本与宿主 AGP 漂移检测（配置期 warn，可选 fail）。 */
internal object AgpToolchainCompatibility {

    const val PINNED_AAPT2_PROTO = "8.13.2-14304508"
    const val PINNED_BUNDLETOOL = "1.17.2"
    const val MIN_AGP_FOR_AAPT2 = "8.10.1"
    const val MIN_AGP_FOR_MAPPING_ARTIFACT = "8.3.0"
    /** AAB artifact transform；8.0.2+ 矩阵 5 探针已验证。 */
    const val MIN_AGP_FOR_BUNDLE_TRANSFORM = "8.0.0"

    fun readAgpVersion(): String? = runCatching {
        Class.forName("com.android.Version")
            .getField("ANDROID_GRADLE_PLUGIN_VERSION")
            .get(null) as String
    }.getOrNull()

    fun requireMinimumAgp(minimum: String, feature: String) {
        val current = readAgpVersion()
        if (current == null || !isAgpAtLeast(current, minimum)) {
            error(
                "molt $feature requires AGP $minimum+; " +
                    "current=${current ?: "unknown"}",
            )
        }
    }

    fun isAgpAtLeast(current: String, minimum: String): Boolean {
        fun parse(version: String): List<Int> =
            version.split('.').map { segment -> segment.toIntOrNull() ?: 0 }
        val currentParts = parse(current)
        val minimumParts = parse(minimum)
        val length = maxOf(currentParts.size, minimumParts.size)
        for (index in 0 until length) {
            val currentPart = currentParts.getOrElse(index) { 0 }
            val minimumPart = minimumParts.getOrElse(index) { 0 }
            if (currentPart != minimumPart) return currentPart > minimumPart
        }
        return true
    }

    fun logWarnings(logger: Logger, failOnMismatch: Boolean = false) {
        val agpVersion = readAgpVersion()
        if (agpVersion == null) {
            val message =
                "molt: unable to read AGP version; " +
                    "verify aapt2-proto=$PINNED_AAPT2_PROTO bundletool=$PINNED_BUNDLETOOL after AGP upgrades"
            if (failOnMismatch) error(message) else logger.warn(message)
            return
        }
        val agpMajorMinor = agpVersion.substringBeforeLast('.').let { raw ->
            raw.split('.').take(2).joinToString(".")
        }
        val pinnedMajorMinor = PINNED_AAPT2_PROTO.substringBefore('-').let { raw ->
            raw.split('.').take(2).joinToString(".")
        }
        if (agpMajorMinor != pinnedMajorMinor) {
            val message =
                "molt: AGP=$agpVersion but plugin pins aapt2-proto=$PINNED_AAPT2_PROTO; " +
                    "run moltObfuscateNightlyVerify after AGP upgrade"
            if (failOnMismatch) error(message) else logger.warn(message)
        } else {
            logger.lifecycle(
                "molt: AGP=$agpVersion aapt2-proto=$PINNED_AAPT2_PROTO bundletool=$PINNED_BUNDLETOOL",
            )
        }
    }
}
