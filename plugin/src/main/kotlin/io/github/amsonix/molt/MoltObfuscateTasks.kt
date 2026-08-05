package io.github.amsonix.molt

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import io.github.amsonix.molt.internal.mapping.MappingMerger
import io.github.amsonix.molt.internal.rename.ClassRenameProtectionScanner
import io.github.amsonix.molt.internal.rename.ComponentScanner
import io.github.amsonix.molt.internal.rename.ProjectSourceIndex
import io.github.amsonix.molt.internal.rename.RenameMapping
import io.github.amsonix.molt.internal.rename.ViewClassScanner
import java.io.File

@CacheableTask
abstract class MoltObfuscateWriteDescriptorTask : DefaultTask() {

    @get:Internal
    abstract val moduleDirectory: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @get:Input
    abstract val sourceRootPaths: ListProperty<String>

    @get:Input
    abstract val manifestPaths: ListProperty<String>

    @get:Input
    abstract val layoutDirPaths: ListProperty<String>

    @get:OutputFile
    abstract val descriptorFile: RegularFileProperty

    init {
        group = "molt"
    }

    @TaskAction
    fun writeDescriptor() {
        val moduleDir = moduleDirectory.get().asFile
        fun resolve(paths: List<String>): List<File> = paths.map { path ->
            File(path).takeIf(File::isAbsolute) ?: File(moduleDir, path)
        }
        ModuleScanDescriptor(
            moduleDir = moduleDir,
            namespace = namespace.orNull?.takeIf(String::isNotBlank),
            sourceRoots = resolve(sourceRootPaths.get()),
            manifestFiles = resolve(manifestPaths.get()),
            layoutDirs = resolve(layoutDirPaths.get()),
        ).writeTo(descriptorFile.get().asFile)
    }
}

@CacheableTask
abstract class MoltObfuscatePrepareMappingTask : DefaultTask() {

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val seed: Property<Int>

    @get:Input
    abstract val excludePatterns: ListProperty<String>

    @get:Input
    abstract val viewRenameEnabled: Property<Boolean>

    @get:Input
    abstract val viewExcludePatterns: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleDescriptors: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scanInputs: ConfigurableFileCollection

    @get:OutputFile
    abstract val mappingJson: RegularFileProperty

    @get:OutputFile
    abstract val mappingReport: RegularFileProperty

    @get:OutputFile
    abstract val viewMappingJson: RegularFileProperty

    @get:OutputFile
    abstract val viewMappingReport: RegularFileProperty

    init {
        group = "molt"
    }

    @TaskAction
    fun prepare() {
        val descriptors = ModuleScanDescriptor.readAll(moduleDescriptors.files)
        val sourceIndex = descriptors.fold(ProjectSourceIndex.EMPTY) { merged, descriptor ->
            merged.mergedWith(
                ComponentScanner.indexSources(
                    namespace = descriptor.namespace,
                    sourceRoots = descriptor.sourceRoots,
                ),
            )
        }
        val candidates = sourceIndex.componentCandidates.toMutableSet()
        descriptors.forEach { descriptor ->
            candidates += ComponentScanner.scanManifests(
                namespace = descriptor.namespace,
                manifestFiles = descriptor.manifestFiles,
            )
            candidates += ComponentScanner.scanRuntimeResourceXml(
                namespace = descriptor.namespace,
                resourceRoots = descriptor.layoutDirs,
            )
        }
        val projectCandidates = ComponentScanner.filterProjectClasses(candidates, sourceIndex.declaredClasses)
        val protectedClasses = ClassRenameProtectionScanner.scan(
            descriptors.flatMap(ModuleScanDescriptor::sourceRoots),
        ).filterTo(linkedSetOf()) { fqcn ->
            ComponentScanner.isProjectClass(fqcn, sourceIndex.declaredClasses)
        }
        val renameCandidates = ComponentScanner
            .filterSupertypeAnchors(projectCandidates, sourceIndex)
            .minus(protectedClasses)
        val mapping = RenameMapping.build(
            candidates = renameCandidates,
            seed = seed.get(),
            excludePatterns = excludePatterns.get(),
        )
        mappingJson.get().asFile.writeText(mapping.toJson())
        MappingMerger.writeComponentMappingReport(
            mappingReport.get().asFile,
            MappingMerger.toProguardMappingLines(
                mapping.entries().associate { it.original to it.obfuscated },
            ),
        )

        if (viewRenameEnabled.get()) {
            val viewCandidates = linkedSetOf<String>()
            descriptors.forEach { descriptor ->
                viewCandidates += ViewClassScanner.scanLayoutDirs(descriptor.layoutDirs)
            }
            val componentOriginals = mapping.entries().map { it.original }.toSet()
            val viewOnly = viewCandidates
                .filter { fqcn -> ComponentScanner.isProjectClass(fqcn, sourceIndex.declaredClasses) }
                .minus(componentOriginals)
                .minus(protectedClasses)
                .toSet()
            val viewMapping = RenameMapping.build(
                candidates = viewOnly,
                seed = seed.get(),
                excludePatterns = viewExcludePatterns.get(),
                salt = "view-rename",
            )
            viewMappingJson.get().asFile.writeText(viewMapping.toJson())
            MappingMerger.writeComponentMappingReport(
                viewMappingReport.get().asFile,
                MappingMerger.toProguardMappingLines(
                    viewMapping.entries().associate { it.original to it.obfuscated },
                ),
            )
        } else {
            viewMappingJson.get().asFile.writeText("{}")
            viewMappingReport.get().asFile.writeText("")
        }
    }
}

@CacheableTask
abstract class MoltObfuscateMergeMappingTask : DefaultTask() {

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputR8Mapping: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentMappingJson: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val viewMappingJson: RegularFileProperty

    @get:Input
    abstract val componentRenameEnabled: Property<Boolean>

    @get:Input
    abstract val viewRenameEnabled: Property<Boolean>

    @get:OutputFile
    abstract val outputMapping: RegularFileProperty

    init {
        group = "molt"
    }

    @TaskAction
    fun merge() {
        val outputFile = outputMapping.get().asFile
        outputFile.parentFile?.mkdirs()
        var merged = inputR8Mapping.orNull?.asFile?.takeIf { it.isFile }?.readText().orEmpty()
        if (componentRenameEnabled.get()) {
            merged = MappingMerger.compose(merged, loadMappingJson(componentMappingJson))
        }
        if (viewRenameEnabled.get()) {
            merged = MappingMerger.compose(merged, loadMappingJson(viewMappingJson))
        }
        outputFile.writeText(merged)
    }

    private fun loadMappingJson(property: RegularFileProperty): RenameMapping =
        RenameMapping.fromJson(property.get().asFile.readText())
}

internal data class ModuleScanDescriptor(
    val moduleDir: File,
    val namespace: String?,
    val sourceRoots: List<File>,
    val manifestFiles: List<File>,
    val layoutDirs: List<File>,
) {
    fun writeTo(file: File) {
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("moduleDir=${moduleDir.pathRelativeTo(file.parentFile)}")
            appendLine("namespace=${namespace.orEmpty()}")
            appendLine("sourceRoots=${sourceRoots.joinToString("|") { it.pathRelativeTo(moduleDir) }}")
            appendLine("manifestFiles=${manifestFiles.joinToString("|") { it.pathRelativeTo(moduleDir) }}")
            appendLine("layoutDirs=${layoutDirs.joinToString("|") { it.pathRelativeTo(moduleDir) }}")
        })
    }

    companion object {
        fun readAll(files: Collection<File>): List<ModuleScanDescriptor> =
            files.filter { it.isFile }.map { read(it) }

        fun read(file: File): ModuleScanDescriptor {
            val lines = file.readLines().associate { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) line to "" else line.substring(0, idx) to line.substring(idx + 1)
            }
            fun resolve(base: File, raw: String): File =
                File(raw).takeIf(File::isAbsolute) ?: File(base, raw)

            val moduleDir = resolve(file.parentFile, lines.getValue("moduleDir"))
            fun splitPaths(raw: String): List<File> = raw.split('|')
                .filter { it.isNotBlank() }
                .map { path -> resolve(moduleDir, path) }
            return ModuleScanDescriptor(
                moduleDir = moduleDir,
                namespace = lines["namespace"]?.takeIf { it.isNotBlank() },
                sourceRoots = splitPaths(lines.getValue("sourceRoots")),
                manifestFiles = splitPaths(lines.getValue("manifestFiles")),
                layoutDirs = splitPaths(lines.getValue("layoutDirs")),
            )
        }
    }
}

private fun File.pathRelativeTo(base: File): String =
    runCatching { absoluteFile.normalize().relativeTo(base.absoluteFile.normalize()).invariantSeparatorsPath }
        .getOrElse { absolutePath }
