package io.github.amsonix.molt.internal.bundle;

import com.android.aapt.Resources;
import io.github.amsonix.molt.internal.keep.KeepXmlParser;
import io.github.amsonix.molt.internal.reschiper.bundle.ResourceMapping;
import io.github.amsonix.molt.internal.reschiper.obfuscation.ResourcesObfuscator;
import io.github.amsonix.molt.internal.reschiper.parser.ResourcesMappingParser;
import io.github.amsonix.molt.internal.resource.ApkImageEntryPatcher;
import io.github.amsonix.molt.internal.util.SeedRandom;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 通过 aapt2 proto APK 改写 resources.pb 与完整 res qualifier 路径。 */
public final class ApkResourceObfuscateEngine {

    private ApkResourceObfuscateEngine() {
    }

    public static final class Config {
        public final File inputApk;
        public final File outputApk;
        public final File aapt2Executable;
        public final int seed;
        public final List<KeepXmlParser.KeepResource> keepRules;
        public final String obfuscationMode;
        public final boolean imageAntiDetectFallback;
        public final boolean imagePerceptualNoise;
        public final String metadataScope;

        public Config(
                File inputApk,
                File outputApk,
                int seed,
                List<KeepXmlParser.KeepResource> keepRules,
                String obfuscationMode
        ) {
            this(
                    inputApk,
                    outputApk,
                    Aapt2ApkConverter.locateExecutable(),
                    seed,
                    keepRules,
                    obfuscationMode,
                    false,
                    ""
            );
        }

        public Config(
                File inputApk,
                File outputApk,
                int seed,
                List<KeepXmlParser.KeepResource> keepRules,
                String obfuscationMode,
                File aapt2Executable
        ) {
            this(
                    inputApk,
                    outputApk,
                    aapt2Executable,
                    seed,
                    keepRules,
                    obfuscationMode,
                    false,
                    ""
            );
        }

        public Config(
                File inputApk,
                File outputApk,
                File aapt2Executable,
                int seed,
                List<KeepXmlParser.KeepResource> keepRules,
                String obfuscationMode
        ) {
            this(
                    inputApk,
                    outputApk,
                    aapt2Executable,
                    seed,
                    keepRules,
                    obfuscationMode,
                    false,
                    ""
            );
        }

        public Config(
                File inputApk,
                File outputApk,
                File aapt2Executable,
                int seed,
                List<KeepXmlParser.KeepResource> keepRules,
                String obfuscationMode,
                boolean imageAntiDetectFallback,
                String metadataScope
        ) {
            this(
                    inputApk,
                    outputApk,
                    aapt2Executable,
                    seed,
                    keepRules,
                    obfuscationMode,
                    imageAntiDetectFallback,
                    metadataScope,
                    null,
                    null
            );
        }

        public final File mappingFile;
        public final File mappingOutputDir;

        public Config(
                File inputApk,
                File outputApk,
                File aapt2Executable,
                int seed,
                List<KeepXmlParser.KeepResource> keepRules,
                String obfuscationMode,
                boolean imageAntiDetectFallback,
                String metadataScope,
                File mappingFile,
                File mappingOutputDir
        ) {
            this(
                    inputApk,
                    outputApk,
                    aapt2Executable,
                    seed,
                    keepRules,
                    obfuscationMode,
                    imageAntiDetectFallback,
                    false,
                    metadataScope,
                    mappingFile,
                    mappingOutputDir
            );
        }

        public Config(
                File inputApk,
                File outputApk,
                File aapt2Executable,
                int seed,
                List<KeepXmlParser.KeepResource> keepRules,
                String obfuscationMode,
                boolean imageAntiDetectFallback,
                boolean imagePerceptualNoise,
                String metadataScope,
                File mappingFile,
                File mappingOutputDir
        ) {
            this.inputApk = inputApk;
            this.outputApk = outputApk;
            this.aapt2Executable = aapt2Executable;
            this.seed = seed;
            this.keepRules = keepRules;
            this.obfuscationMode = obfuscationMode;
            this.imageAntiDetectFallback = imageAntiDetectFallback;
            this.imagePerceptualNoise = imagePerceptualNoise;
            this.metadataScope = metadataScope == null ? "" : metadataScope;
            this.mappingFile = mappingFile;
            this.mappingOutputDir = mappingOutputDir;
        }
    }

    public static final class Result {
        public final int renamedEntryCount;
        public final int renamedPathCount;
        public final boolean tableRewritten;
        public final java.util.List<io.github.amsonix.molt.internal.resource.ImagePatchRecord> imagePatchRecords;

        public Result(
                int renamedEntryCount,
                int renamedPathCount,
                boolean tableRewritten,
                java.util.List<io.github.amsonix.molt.internal.resource.ImagePatchRecord> imagePatchRecords
        ) {
            this.renamedEntryCount = renamedEntryCount;
            this.renamedPathCount = renamedPathCount;
            this.tableRewritten = tableRewritten;
            this.imagePatchRecords = imagePatchRecords;
        }
    }

    public static Result obfuscate(Config config) throws IOException {
        validateConfig(config);
        java.util.List<io.github.amsonix.molt.internal.resource.ImagePatchRecord> lastRewriteRecords =
                new java.util.ArrayList<>();
        File parent = config.outputApk.getParentFile();
        File tempDirectory = parent != null ? parent : config.inputApk.getAbsoluteFile().getParentFile();
        File protoInput = null;
        File protoOutput = null;
        try {
            protoInput = File.createTempFile("shell-resource-input-", ".apk", tempDirectory);
            protoOutput = File.createTempFile("shell-resource-output-", ".apk", tempDirectory);
            Aapt2ApkConverter.convert(
                    config.aapt2Executable,
                    config.inputApk,
                    protoInput,
                    Aapt2ApkConverter.Format.PROTO
            );
            ResourceTableData tableData = readResourceTable(protoInput);
            ResourceMapping incrementalMapping = loadIncrementalMapping(config.mappingFile);
            ApkResourceTableRewriter.Plan plan = ApkResourceTableRewriter.createPlan(
                    tableData.table,
                    config.keepRules,
                    config.obfuscationMode,
                    SeedRandom.INSTANCE.create(config.seed, "apk-resource-obfuscate"),
                    incrementalMapping
            );
            byte[] rewrittenTable = plan.hasChanges()
                    ? ApkResourceTableRewriter.rewrite(tableData.table, plan)
                    : tableData.bytes;
            rewriteProtoApk(
                    protoInput,
                    protoOutput,
                    tableData.bytes,
                    rewrittenTable,
                    plan.directoryNameMap,
                    plan.filePathMap,
                    config,
                    lastRewriteRecords
            );
            Aapt2ApkConverter.convert(
                    config.aapt2Executable,
                    protoOutput,
                    config.outputApk,
                    Aapt2ApkConverter.Format.BINARY
            );
            writeMappingIfNeeded(config, tableData.table, plan);
            return new Result(
                    plan.entryNameMap.size(),
                    plan.filePathMap.size(),
                    plan.hasChanges(),
                    lastRewriteRecords
            );
        } finally {
            if (protoInput != null) {
                protoInput.delete();
            }
            if (protoOutput != null) {
                protoOutput.delete();
            }
        }
    }

    private static ResourceMapping loadIncrementalMapping(File mappingFile) throws IOException {
        if (mappingFile == null || !mappingFile.isFile()) {
            return null;
        }
        return new ResourcesMappingParser(mappingFile.toPath()).parse();
    }

    private static void writeMappingIfNeeded(
            Config config,
            Resources.ResourceTable table,
            ApkResourceTableRewriter.Plan plan
    ) throws IOException {
        if (config.mappingOutputDir == null || !plan.hasChanges()) {
            return;
        }
        if (!config.mappingOutputDir.isDirectory() && !config.mappingOutputDir.mkdirs()) {
            throw new IOException("cannot create mapping output dir: " + config.mappingOutputDir);
        }
        ApkResourceMappingWriter.write(
                table,
                plan,
                new File(config.mappingOutputDir, ResourcesObfuscator.FILE_MAPPING_NAME)
        );
    }

    private static void validateConfig(Config config) throws IOException {
        if (!config.inputApk.isFile()) {
            throw new IllegalArgumentException("input APK not found: " + config.inputApk.getPath());
        }
        if (config.aapt2Executable == null) {
            throw new IllegalStateException(
                    "aapt2 executable not found; set ANDROID_HOME or ANDROID_SDK_ROOT"
            );
        }
        if (config.inputApk.getCanonicalFile().equals(config.outputApk.getCanonicalFile())) {
            throw new IllegalArgumentException("input and output APK must differ: " + config.inputApk);
        }
        File parent = config.outputApk.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create output directory: " + parent);
        }
        if (config.outputApk.exists() && !config.outputApk.delete()) {
            throw new IOException("cannot delete stale output APK: " + config.outputApk);
        }
    }

    private static ResourceTableData readResourceTable(File protoApk) throws IOException {
        try (ZipFile zipFile = new ZipFile(protoApk)) {
            ZipEntry entry = zipFile.getEntry("resources.pb");
            if (entry == null) {
                throw new IOException("proto APK is missing resources.pb: " + protoApk);
            }
            byte[] bytes;
            try (java.io.InputStream input = zipFile.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
            if (bytes.length == 0) {
                throw new IOException("proto APK resources.pb is empty: " + protoApk);
            }
            try {
                return new ResourceTableData(bytes, Resources.ResourceTable.parseFrom(bytes));
            } catch (Exception e) {
                throw new IOException("failed to parse proto APK resources.pb", e);
            }
        }
    }

    private static void rewriteProtoApk(
            File input,
            File output,
            byte[] originalTable,
            byte[] rewrittenTable,
            Map<String, String> directoryNameMap,
            Map<String, String> filePathMap,
            Config config,
            java.util.List<io.github.amsonix.molt.internal.resource.ImagePatchRecord> patchRecords
    ) throws IOException {
        try (ZipFile zipIn = new ZipFile(input);
             ZipOutputStream zipOut = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(output))
             )) {
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String outputName = remapZipEntry(
                        entry.getName(),
                        directoryNameMap,
                        filePathMap
                );
                if ("resources.pb".equals(entry.getName())) {
                    ZipEntryWriter.writeBytes(
                            zipOut,
                            entry,
                            outputName,
                            rewrittenTable,
                            !Arrays.equals(originalTable, rewrittenTable),
                            false
                    );
                } else if (config.imageAntiDetectFallback
                        && ApkImageEntryPatcher.isImageEntry(outputName)) {
                    byte[] original;
                    try (java.io.InputStream inputStream = zipIn.getInputStream(entry)) {
                        original = inputStream.readAllBytes();
                    }
                    byte[] patched = ApkImageEntryPatcher.patchIfNeeded(
                            outputName,
                            original,
                            config.seed,
                            config.metadataScope,
                            true,
                            config.imagePerceptualNoise
                    );
                    if (!Arrays.equals(original, patched)) {
                        patchRecords.add(
                                new io.github.amsonix.molt.internal.resource.ImagePatchRecord(
                                        outputName,
                                        io.github.amsonix.molt.internal.resource.ImageMetadataAntiDetectProcessor.INSTANCE.md5Hex(original),
                                        io.github.amsonix.molt.internal.resource.ImageMetadataAntiDetectProcessor.INSTANCE.md5Hex(patched)
                                )
                        );
                    }
                    ZipEntryWriter.writeBytes(
                            zipOut,
                            entry,
                            outputName,
                            patched,
                            !Arrays.equals(original, patched),
                            false
                    );
                } else {
                    ZipEntryWriter.copy(zipOut, zipIn, entry, outputName);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("failed to rewrite proto APK ZIP", e);
        }
    }

    static String remapZipEntry(
            String name,
            Map<String, String> directoryNameMap,
            Map<String, String> filePathMap
    ) {
        if (!name.startsWith("res/")) {
            return name;
        }
        if (name.endsWith("/")) {
            String directory = name.substring(0, name.length() - 1);
            return directoryNameMap.getOrDefault(directory, directory) + "/";
        }
        return filePathMap.getOrDefault(name, name);
    }

    static String nextUniqueName(Random random, Set<String> used, char prefix) {
        return ApkResourceTableRewriter.nextUniqueName(random, used, prefix);
    }

    static String nextUniqueFilePath(
            ApkResourcePath path,
            String mappedDirectory,
            String mappedBaseName,
            Set<String> usedTargetPaths
    ) {
        return ApkResourceTableRewriter.nextUniqueFilePath(
                path,
                mappedDirectory,
                mappedBaseName,
                usedTargetPaths
        );
    }

    private static final class ResourceTableData {
        private final byte[] bytes;
        private final Resources.ResourceTable table;

        private ResourceTableData(byte[] bytes, Resources.ResourceTable table) {
            this.bytes = bytes;
            this.table = table;
        }
    }
}
