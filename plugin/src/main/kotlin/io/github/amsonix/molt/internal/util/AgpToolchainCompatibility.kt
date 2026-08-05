package io.github.amsonix.molt.internal.util

import org.gradle.api.logging.Logger

/** 插件 pin 版本与宿主 AGP 漂移检测（配置期 warn，可选 fail）。 */
internal object AgpToolchainCompatibility {

    const val PINNED_AAPT2_PROTO = "8.13.2-14304508"
    const val PINNED_BUNDLETOOL = "1.17.2"

    fun logWarnings(logger: Logger, failOnMismatch: Boolean = false) {
        val agpVersion = runCatching {
            Class.forName("com.android.Version")
                .getField("ANDROID_GRADLE_PLUGIN_VERSION")
                .get(null) as String
        }.getOrNull()
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
