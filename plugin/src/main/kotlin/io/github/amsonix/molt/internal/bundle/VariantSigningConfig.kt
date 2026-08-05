package io.github.amsonix.molt.internal.bundle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ApkSigningConfig
import org.gradle.api.Project
import java.io.File

internal data class SigningConfigSnapshot(
    val storeFile: File?,
    val storePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?,
) {
    val isComplete: Boolean
        get() = storeFile != null && storeFile.isFile &&
            !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank()
}

internal object VariantSigningConfig {

    fun fromBuildType(project: Project, buildTypeName: String?): SigningConfigSnapshot {
        if (buildTypeName.isNullOrBlank()) return SigningConfigSnapshot(null, null, null, null)
        val android = project.extensions.findByType(ApplicationExtension::class.java) ?: return empty()
        val signing = android.buildTypes.findByName(buildTypeName)?.signingConfig ?: return empty()
        return fromSigningConfig(signing)
    }

    private fun fromSigningConfig(signing: ApkSigningConfig?): SigningConfigSnapshot {
        if (signing == null) return empty()
        return SigningConfigSnapshot(
            storeFile = signing.storeFile,
            storePassword = signing.storePassword,
            keyAlias = signing.keyAlias,
            keyPassword = signing.keyPassword,
        )
    }

    private fun empty() = SigningConfigSnapshot(null, null, null, null)
}
