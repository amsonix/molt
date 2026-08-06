package io.github.amsonix.molt.internal.util

import com.android.build.api.dsl.SdkComponents
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import io.github.amsonix.molt.internal.bundle.AndroidBuildToolLocator

/** Resolve aapt2 executable: AGP 8.10.1+ via [SdkComponents.aapt2], else SDK build-tools. */
internal object Aapt2ExecutableResolver {
    fun resolve(project: Project, sdkComponents: SdkComponents): Provider<RegularFile> {
        val agpVersion = AgpToolchainCompatibility.readAgpVersion()
        return if (agpVersion != null &&
            AgpToolchainCompatibility.isAgpAtLeast(agpVersion, AgpToolchainCompatibility.MIN_AGP_FOR_AAPT2)
        ) {
            sdkComponents.aapt2.flatMap { it.executable }
        } else {
            sdkComponents.sdkDirectory.map { sdkDir ->
                val file = AndroidBuildToolLocator.locateInSdk(sdkDir.asFile, "aapt2")
                    ?: AndroidBuildToolLocator.require("aapt2")
                project.objects.fileProperty().fileValue(file).get()
            }
        }
    }
}
