package io.github.amsonix.molt

import com.android.build.api.variant.ApplicationVariant
import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import io.github.amsonix.molt.internal.util.AppLibraryDependencyGraph
import io.github.amsonix.molt.internal.util.variantCapitalizedName
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

/** descriptor 任务注册、library 依赖图接线与 source set 收集。 */
internal object MoltObfuscateDescriptorWiring {

    fun scheduleLibraryAutoWire(
        appProject: Project,
        variantName: String,
        prepareTask: TaskProvider<MoltObfuscatePrepareMappingTask>,
    ) {
        AppLibraryDependencyGraph.resolveLibraryProjects(
            appProject,
            listOf(variantName),
        ).forEach { library ->
            wireLibraryCandidate(library, prepareTask)
        }
    }

    fun registerVariantDescriptorTask(
        project: Project,
        android: BaseExtension,
        variantName: String,
        sourceRoots: List<File>,
        manifests: List<File>,
        layoutDirs: List<File>,
    ): TaskProvider<MoltObfuscateWriteDescriptorTask> {
        val capitalized = variantCapitalizedName(variantName)
        val descriptorTask = project.tasks.register<MoltObfuscateWriteDescriptorTask>(
            "moltObfuscateWriteDescriptor$capitalized",
        ) {
            description = "Write module scan descriptor for $variantName"
            moduleDirectory.set(project.layout.projectDirectory)
            descriptorFile.set(
                project.layout.buildDirectory.file("shell-obfuscate/$variantName/module-scan.descriptor"),
            )
        }
        configureDescriptorTask(project, android, descriptorTask, sourceRoots, manifests, layoutDirs)
        return descriptorTask
    }

    fun addDescriptorToPrepare(
        project: Project,
        android: BaseExtension,
        descriptorTask: TaskProvider<MoltObfuscateWriteDescriptorTask>,
        prepareTask: TaskProvider<MoltObfuscatePrepareMappingTask>,
        sourceRoots: List<File> = collectDescriptorSourceRoots(project, android),
        manifests: List<File> = collectDescriptorManifestFiles(android),
        layoutDirs: List<File> = collectDescriptorLayoutDirs(android),
    ) {
        prepareTask.configure {
            dependsOn(descriptorTask)
            moduleDescriptors.from(descriptorTask.flatMap { task -> task.descriptorFile })
            scanInputs.from(sourceRoots + manifests + layoutDirs)
        }
    }

    fun collectVariantSourceSets(
        android: AppExtension,
        variant: ApplicationVariant,
    ) = buildList {
        add("main")
        addAll(variant.productFlavors.map { (_, flavorName) -> flavorName })
        variant.buildType?.let { buildType ->
            val combinedFlavor = variant.name.removeSuffix(variantCapitalizedName(buildType))
            if (combinedFlavor.isNotBlank() && combinedFlavor != variant.name) add(combinedFlavor)
            add(buildType)
        }
        add(variant.name)
    }.distinct().mapNotNull(android.sourceSets::findByName)

    fun collectLayoutDirs(resDirs: Iterable<File>): List<File> =
        resDirs.flatMap { resDir ->
            resDir.listFiles()
                .orEmpty()
                .filter { directory ->
                    directory.isDirectory &&
                        (directory.name.startsWith("layout") || directory.name.startsWith("navigation"))
                }
        }.distinctBy { it.absoluteFile.normalize() }

    private fun wireLibraryCandidate(
        library: Project,
        prepareTask: TaskProvider<MoltObfuscatePrepareMappingTask>,
    ) {
        val descriptorTask = registerSharedDescriptorTask(library) ?: return
        val android = library.extensions.findByType(BaseExtension::class.java) ?: return
        addDescriptorToPrepare(library, android, descriptorTask, prepareTask)
    }

    private fun registerSharedDescriptorTask(
        project: Project,
    ): TaskProvider<MoltObfuscateWriteDescriptorTask>? {
        val android = project.extensions.findByType(BaseExtension::class.java) ?: return null
        val existing = project.tasks.findByName("moltObfuscateWriteDescriptor")
        val descriptorTask = if (existing != null) {
            project.tasks.named("moltObfuscateWriteDescriptor", MoltObfuscateWriteDescriptorTask::class.java)
        } else {
            project.tasks.register<MoltObfuscateWriteDescriptorTask>("moltObfuscateWriteDescriptor") {
                description = "Write module scan descriptor"
                moduleDirectory.set(project.layout.projectDirectory)
                descriptorFile.set(project.layout.buildDirectory.file("shell-obfuscate/module-scan.descriptor"))
            }
        }
        configureDescriptorTask(
            project = project,
            android = android,
            descriptorTask = descriptorTask,
            sourceRoots = collectDescriptorSourceRoots(project, android),
            manifests = collectDescriptorManifestFiles(android),
            layoutDirs = collectDescriptorLayoutDirs(android),
        )
        return descriptorTask
    }

    private fun configureDescriptorTask(
        project: Project,
        android: BaseExtension,
        descriptorTask: TaskProvider<MoltObfuscateWriteDescriptorTask>,
        sourceRoots: List<File>,
        manifests: List<File>,
        layoutDirs: List<File>,
    ) {
        descriptorTask.configure {
            namespace.set(
                when (android) {
                    is AppExtension -> android.namespace
                    is LibraryExtension -> android.namespace
                    else -> null
                }.orEmpty(),
            )
            sourceRootPaths.set(sourceRoots.map { it.pathRelativeTo(project.projectDir) })
            manifestPaths.set(manifests.map { it.pathRelativeTo(project.projectDir) })
            layoutDirPaths.set(layoutDirs.map { it.pathRelativeTo(project.projectDir) })
        }
    }

    private fun collectDescriptorSourceRoots(
        project: Project,
        android: BaseExtension,
    ): List<File> = (
        listOf(
            File(project.projectDir, "src/main/java"),
            File(project.projectDir, "src/main/kotlin"),
        ) + android.sourceSets.flatMap { it.java.srcDirs }
    ).distinctBy { it.absoluteFile.normalize() }

    private fun collectDescriptorManifestFiles(android: BaseExtension): List<File> =
        android.sourceSets.map { it.manifest.srcFile }
            .distinctBy { it.absoluteFile.normalize() }

    private fun collectDescriptorLayoutDirs(android: BaseExtension): List<File> =
        collectLayoutDirs(android.sourceSets.flatMap { set -> set.res.srcDirs })
}

private fun File.pathRelativeTo(base: File): String =
    runCatching { absoluteFile.normalize().relativeTo(base.absoluteFile.normalize()).invariantSeparatorsPath }
        .getOrElse { absolutePath }
