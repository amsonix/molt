package io.github.amsonix.molt.internal.util

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.Variant
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/** R8 mapping: AGP 8.3+ artifact API; older AGP fall back to outputs/mapping/{variant}/mapping.txt. */
internal object ObfuscationMappingFileResolver {
    private const val MIN_AGP_FOR_MAPPING_ARTIFACT = AgpToolchainCompatibility.MIN_AGP_FOR_MAPPING_ARTIFACT

    fun resolve(project: Project, variant: Variant): Provider<RegularFile> {
        val fromArtifact = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        val agpVersion = AgpToolchainCompatibility.readAgpVersion()
        if (agpVersion != null &&
            AgpToolchainCompatibility.isAgpAtLeast(agpVersion, MIN_AGP_FOR_MAPPING_ARTIFACT)
        ) {
            return fromArtifact
        }
        val legacyPath = project.layout.buildDirectory.file("outputs/mapping/${variant.name}/mapping.txt")
        return fromArtifact.flatMap { artifact ->
            project.provider {
                if (artifact.asFile.isFile) {
                    artifact
                } else {
                    legacyPath.get()
                }
            }
        }
    }
}
