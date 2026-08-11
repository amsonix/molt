package io.github.amsonix.molt

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.ApplicationVariant
import io.github.amsonix.molt.internal.util.AppLibraryDependencyGraph
import io.github.amsonix.molt.internal.util.SourceSetDirectoriesCompat
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
        android: CommonExtension<*, *, *, *, *, *>,
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
        android: CommonExtension<*, *, *, *, *, *>,
        descriptorTask: TaskProvider<MoltObfuscateWriteDescriptorTask>,
        prepareTask: TaskProvider<MoltObfuscatePrepareMappingTask>,
        sourceRoots: List<File> = collectDescriptorSourceRoots(project, android),
        manifests: List<File> = collectDescriptorManifestFiles(project, android),
        layoutDirs: List<File> = collectDescriptorLayoutDirs(project, android),
    ) {
        prepareTask.configure {
            dependsOn(descriptorTask)
            moduleDescriptors.from(descriptorTask.flatMap { task -> task.descriptorFile })
            scanInputs.from(sourceRoots + manifests + layoutDirs)
        }
    }

    fun collectVariantSourceSetNames(variant: ApplicationVariant): List<String> =
        collectVariantSourceSetNames(
            variantName = variant.name,
            productFlavors = variant.productFlavors,
            buildType = variant.buildType,
        )

    fun collectVariantSourceSetNames(
        variantName: String,
        productFlavors: List<Pair<String, String>>,
        buildType: String?,
    ): List<String> =
        buildList {
            add("main")
            addAll(productFlavors.map { (_, flavorName) -> flavorName })
            buildType?.let { type ->
                val combinedFlavor = variantName.removeSuffix(variantCapitalizedName(type))
                if (combinedFlavor.isNotBlank() && combinedFlavor != variantName) add(combinedFlavor)
                add(type)
            }
            add(variantName)
        }.distinct()

    fun collectVariantSourceSets(
        android: CommonExtension<*, *, *, *, *, *>,
        variant: ApplicationVariant,
    ) = collectVariantSourceSetNames(variant).mapNotNull(android.sourceSets::findByName)

    /**
     * variant 级 res 源目录；AGP 在 [onVariants] 时 [SourceSet.res.srcDirs] 可能尚未就绪，
     * 因此由调用方通过 [org.gradle.api.provider.Provider] 延迟到 task 执行前再解析。
     */
    fun collectVariantResDirs(
        project: Project,
        android: CommonExtension<*, *, *, *, *, *>,
        variant: ApplicationVariant,
    ): List<File> = collectVariantResDirs(
        project = project,
        android = android,
        sourceSetNames = collectVariantSourceSetNames(variant),
    )

    fun collectVariantResDirs(
        project: Project,
        android: CommonExtension<*, *, *, *, *, *>,
        sourceSetNames: List<String>,
    ): List<File> {
        val fromAgp = sourceSetNames.mapNotNull(android.sourceSets::findByName)
            .flatMap { SourceSetDirectoriesCompat.of(project, it, "getRes") }
            .filter { it.isDirectory && isProjectResSourceDir(project, it) }
            .distinctBy { it.absoluteFile.normalize() }
        val fromSourceTree = conventionalVariantResDirs(project, sourceSetNames)
        return (fromSourceTree + fromAgp)
            .filter { it.isDirectory }
            .distinctBy { it.absoluteFile.normalize() }
    }

    private fun isProjectResSourceDir(project: Project, dir: File): Boolean {
        val srcRoot = File(project.projectDir, "src").absoluteFile.normalize()
        val normalized = dir.absoluteFile.normalize()
        return normalized.path == srcRoot.path ||
            normalized.path.startsWith(srcRoot.path + File.separator)
    }

    internal fun conventionalVariantResDirs(
        project: Project,
        sourceSetNames: Iterable<String>,
    ): List<File> =
        sourceSetNames
            .map { name -> File(project.projectDir, "src/$name/res") }
            .filter { it.isDirectory }
            .distinctBy { it.absoluteFile.normalize() }

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
        val android = library.extensions.findByType(CommonExtension::class.java) ?: return
        addDescriptorToPrepare(library, android, descriptorTask, prepareTask)
    }

    private fun registerSharedDescriptorTask(
        project: Project,
    ): TaskProvider<MoltObfuscateWriteDescriptorTask>? {
        val android = project.extensions.findByType(CommonExtension::class.java) ?: return null
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
            manifests = collectDescriptorManifestFiles(project, android),
            layoutDirs = collectDescriptorLayoutDirs(project, android),
        )
        return descriptorTask
    }

    private fun configureDescriptorTask(
        project: Project,
        android: CommonExtension<*, *, *, *, *, *>,
        descriptorTask: TaskProvider<MoltObfuscateWriteDescriptorTask>,
        sourceRoots: List<File>,
        manifests: List<File>,
        layoutDirs: List<File>,
    ) {
        descriptorTask.configure {
            namespace.set(android.namespace)
            sourceRootPaths.set(sourceRoots.map { it.pathRelativeTo(project.projectDir) })
            manifestPaths.set(manifests.map { it.pathRelativeTo(project.projectDir) })
            layoutDirPaths.set(layoutDirs.map { it.pathRelativeTo(project.projectDir) })
        }
    }

    private fun collectDescriptorSourceRoots(
        project: Project,
        android: CommonExtension<*, *, *, *, *, *>,
    ): List<File> = (
        listOf(
            File(project.projectDir, "src/main/java"),
            File(project.projectDir, "src/main/kotlin"),
        ) + android.sourceSets.flatMap { SourceSetDirectoriesCompat.of(project, it, "getJava") }
    ).distinctBy { it.absoluteFile.normalize() }

    private fun collectDescriptorManifestFiles(
        project: Project,
        android: CommonExtension<*, *, *, *, *, *>,
    ): List<File> =
        android.sourceSets
            .mapNotNull { sourceSet ->
                // 新 DSL AndroidSourceFile 无读取访问器；manifest 采用约定路径。
                File(project.projectDir, "src/${sourceSet.name}/AndroidManifest.xml")
                    .takeIf { it.isFile }
            }
            .distinctBy { it.absoluteFile.normalize() }

    private fun collectDescriptorLayoutDirs(
        project: Project,
        android: CommonExtension<*, *, *, *, *, *>,
    ): List<File> =
        collectLayoutDirs(android.sourceSets.flatMap { set -> SourceSetDirectoriesCompat.of(project, set, "getRes") })
}

private fun File.pathRelativeTo(base: File): String =
    runCatching { absoluteFile.normalize().relativeTo(base.absoluteFile.normalize()).invariantSeparatorsPath }
        .getOrElse { absolutePath }
