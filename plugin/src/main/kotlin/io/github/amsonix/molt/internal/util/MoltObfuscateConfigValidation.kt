package io.github.amsonix.molt.internal.util

private val VALID_OBFUSCATION_MODES = setOf("default", "dir", "file")

internal fun requireValidObfuscationMode(mode: String): String {
    require(mode in VALID_OBFUSCATION_MODES) {
        "molt.bundleResourceObfuscate.obfuscationMode must be one of " +
            "${VALID_OBFUSCATION_MODES.joinToString()} (was '$mode')"
    }
    return mode
}
