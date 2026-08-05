package io.github.amsonix.molt.internal.bundle;

import com.android.aapt.Resources;
import io.github.amsonix.molt.internal.keep.KeepFilter;
import io.github.amsonix.molt.internal.keep.KeepXmlParser;
import io.github.amsonix.molt.internal.reschiper.bundle.ResourceMapping;
import io.github.amsonix.molt.internal.reschiper.operations.ResourceTableOperation;
import io.github.amsonix.molt.internal.util.ObfuscateNaming;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** 生成资源名、完整文件路径映射并改写 proto ResourceTable。 */
final class ApkResourceTableRewriter {

    private ApkResourceTableRewriter() {
    }

    static Plan createPlan(
            Resources.ResourceTable table,
            List<KeepXmlParser.KeepResource> keepRules,
            String obfuscationMode,
            Random random
    ) throws IOException {
        return createPlan(table, keepRules, obfuscationMode, random, null);
    }

    static Plan createPlan(
            Resources.ResourceTable table,
            List<KeepXmlParser.KeepResource> keepRules,
            String obfuscationMode,
            Random random,
            ResourceMapping incrementalMapping
    ) throws IOException {
        Map<String, String> entryNameMap = new HashMap<>();
        Map<String, String> directoryNameMap = new HashMap<>();
        if (incrementalMapping != null) {
            seedFromIncrementalMapping(incrementalMapping, entryNameMap, directoryNameMap);
        }
        collectMappings(
                table,
                keepRules,
                obfuscationMode,
                random,
                entryNameMap,
                directoryNameMap
        );
        Map<String, String> filePathMap = collectFilePathMappings(
                table,
                keepRules,
                obfuscationMode,
                entryNameMap,
                directoryNameMap
        );
        return new Plan(entryNameMap, directoryNameMap, filePathMap);
    }

    static byte[] rewrite(Resources.ResourceTable table, Plan plan) throws IOException {
        try {
            Resources.ResourceTable.Builder builder = table.toBuilder();
            for (Resources.Package.Builder pkg : builder.getPackageBuilderList()) {
                for (Resources.Type.Builder type : pkg.getTypeBuilderList()) {
                    String resType = type.getName();
                    for (Resources.Entry.Builder entry : type.getEntryBuilderList()) {
                        String mappedName = plan.entryNameMap.get(entryKey(resType, entry.getName()));
                        if (mappedName != null) {
                            entry.setName(mappedName);
                        }
                        for (int i = 0; i < entry.getConfigValueCount(); i++) {
                            Resources.ConfigValue configValue = entry.getConfigValue(i);
                            if (!configValue.getValue().getItem().hasFile()) {
                                continue;
                            }
                            String sourcePath = normalizePath(
                                    configValue.getValue().getItem().getFile().getPath()
                            );
                            String updatedPath = plan.filePathMap.get(sourcePath);
                            if (updatedPath != null) {
                                entry.setConfigValue(
                                        i,
                                        ResourceTableOperation.replaceEntryPath(configValue, updatedPath)
                                );
                            }
                        }
                    }
                }
            }
            return builder.build().toByteArray();
        } catch (RuntimeException e) {
            throw new IOException("failed to rewrite proto APK resources.pb", e);
        }
    }

    private static void collectMappings(
            Resources.ResourceTable table,
            List<KeepXmlParser.KeepResource> keepRules,
            String obfuscationMode,
            Random random,
            Map<String, String> entryNameMap,
            Map<String, String> directoryNameMap
    ) throws IOException {
        Map<String, Set<String>> usedEntryNames = collectUsedEntryNames(table);
        reserveSeededEntryNames(entryNameMap, usedEntryNames);
        Set<String> usedDirectoryNames = collectUsedDirectoryNames(table);
        reserveSeededDirectoryNames(directoryNameMap, usedDirectoryNames);
        for (Resources.Package pkg : table.getPackageList()) {
            for (Resources.Type type : pkg.getTypeList()) {
                String resType = type.getName();
                for (Resources.Entry entry : type.getEntryList()) {
                    if (!KeepFilter.INSTANCE.shouldObfuscate(resType, entry.getName(), keepRules)) {
                        continue;
                    }
                    String key = entryKey(resType, entry.getName());
                    Set<String> usedForType = usedEntryNames.computeIfAbsent(
                            resType,
                            ignored -> new HashSet<>()
                    );
                    entryNameMap.computeIfAbsent(
                            key,
                            ignored -> nextUniqueName(
                                    random,
                                    usedForType,
                                    resType.isEmpty() ? 'a' : resType.charAt(0)
                            )
                    );
                    if (!"file".equals(obfuscationMode)) {
                        for (Resources.ConfigValue configValue : entry.getConfigValueList()) {
                            if (!configValue.getValue().getItem().hasFile()) {
                                continue;
                            }
                            ApkResourcePath path = parseResourcePath(
                                    configValue.getValue().getItem().getFile().getPath()
                            );
                            directoryNameMap.computeIfAbsent(
                                    path.getDirectory(),
                                    ignored -> "res/" + nextUniqueName(
                                            random,
                                            usedDirectoryNames,
                                            'a'
                                    )
                            );
                        }
                    }
                }
            }
        }
    }

    private static Map<String, String> collectFilePathMappings(
            Resources.ResourceTable table,
            List<KeepXmlParser.KeepResource> keepRules,
            String obfuscationMode,
            Map<String, String> entryNameMap,
            Map<String, String> directoryNameMap
    ) throws IOException {
        Map<String, String> filePathMap = new HashMap<>();
        Set<String> sourcePaths = collectFilePaths(table);
        Set<String> keptFilePaths = collectKeptFilePaths(table, keepRules);
        Set<String> handledPaths = new HashSet<>();
        Set<String> usedTargetPaths = new HashSet<>(sourcePaths);
        for (Resources.Package pkg : table.getPackageList()) {
            for (Resources.Type type : pkg.getTypeList()) {
                String resourceType = type.getName();
                for (Resources.Entry entry : type.getEntryList()) {
                    for (Resources.ConfigValue configValue : entry.getConfigValueList()) {
                        if (!configValue.getValue().getItem().hasFile()) {
                            continue;
                        }
                        String sourcePath = normalizePath(
                                configValue.getValue().getItem().getFile().getPath()
                        );
                        if (!handledPaths.add(sourcePath) || keptFilePaths.contains(sourcePath)) {
                            continue;
                        }
                        ApkResourcePath path = parseResourcePath(sourcePath);
                        String mappedDirectory = directoryNameMap.getOrDefault(
                                path.getDirectory(),
                                path.getDirectory()
                        );
                        String mappedBaseName = "dir".equals(obfuscationMode)
                                ? path.getBaseName()
                                : entryNameMap.getOrDefault(
                                        entryKey(resourceType, entry.getName()),
                                        path.getBaseName()
                                );
                        String candidate = path.remap(mappedDirectory, mappedBaseName);
                        if (!candidate.equals(sourcePath)) {
                            filePathMap.put(
                                    sourcePath,
                                    nextUniqueFilePath(
                                            path,
                                            mappedDirectory,
                                            mappedBaseName,
                                            usedTargetPaths
                                    )
                            );
                        }
                    }
                }
            }
        }
        return filePathMap;
    }

    private static Set<String> collectKeptFilePaths(
            Resources.ResourceTable table,
            List<KeepXmlParser.KeepResource> keepRules
    ) {
        Set<String> paths = new HashSet<>();
        for (Resources.Package pkg : table.getPackageList()) {
            for (Resources.Type type : pkg.getTypeList()) {
                for (Resources.Entry entry : type.getEntryList()) {
                    if (KeepFilter.INSTANCE.shouldObfuscate(
                            type.getName(),
                            entry.getName(),
                            keepRules
                    )) {
                        continue;
                    }
                    for (Resources.ConfigValue configValue : entry.getConfigValueList()) {
                        if (configValue.getValue().getItem().hasFile()) {
                            paths.add(normalizePath(
                                    configValue.getValue().getItem().getFile().getPath()
                            ));
                        }
                    }
                }
            }
        }
        return paths;
    }

    private static Map<String, Set<String>> collectUsedEntryNames(Resources.ResourceTable table) {
        Map<String, Set<String>> namesByType = new HashMap<>();
        for (Resources.Package pkg : table.getPackageList()) {
            for (Resources.Type type : pkg.getTypeList()) {
                Set<String> names = namesByType.computeIfAbsent(
                        type.getName(),
                        ignored -> new HashSet<>()
                );
                for (Resources.Entry entry : type.getEntryList()) {
                    names.add(entry.getName());
                }
            }
        }
        return namesByType;
    }

    private static Set<String> collectUsedDirectoryNames(
            Resources.ResourceTable table
    ) throws IOException {
        Set<String> names = new HashSet<>();
        for (String path : collectFilePaths(table)) {
            String directory = parseResourcePath(path).getDirectory();
            names.add(directory.substring(directory.lastIndexOf('/') + 1));
        }
        return names;
    }

    private static Set<String> collectFilePaths(Resources.ResourceTable table) {
        Set<String> paths = new HashSet<>();
        for (Resources.Package pkg : table.getPackageList()) {
            for (Resources.Type type : pkg.getTypeList()) {
                for (Resources.Entry entry : type.getEntryList()) {
                    for (Resources.ConfigValue configValue : entry.getConfigValueList()) {
                        if (configValue.getValue().getItem().hasFile()) {
                            paths.add(normalizePath(
                                    configValue.getValue().getItem().getFile().getPath()
                            ));
                        }
                    }
                }
            }
        }
        return paths;
    }

    static String nextUniqueName(Random random, Set<String> used, char prefix) {
        String candidate;
        do {
            candidate = ObfuscateNaming.INSTANCE.nextResourceName(random, prefix);
        } while (!used.add(candidate));
        return candidate;
    }

    static String nextUniqueFilePath(
            ApkResourcePath path,
            String mappedDirectory,
            String mappedBaseName,
            Set<String> usedTargetPaths
    ) {
        String candidate = path.remap(mappedDirectory, mappedBaseName);
        int suffix = 1;
        while (!usedTargetPaths.add(candidate)) {
            candidate = path.remap(mappedDirectory, mappedBaseName + "_" + suffix++);
        }
        return candidate;
    }

    private static ApkResourcePath parseResourcePath(String path) throws IOException {
        try {
            return ApkResourcePath.parse(path);
        } catch (IllegalArgumentException e) {
            throw new IOException("failed to parse APK resource path: " + path, e);
        }
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private static void seedFromIncrementalMapping(
            ResourceMapping incrementalMapping,
            Map<String, String> entryNameMap,
            Map<String, String> directoryNameMap
    ) {
        directoryNameMap.putAll(incrementalMapping.getDirMapping());
        entryNameMap.putAll(incrementalMapping.getResourceMapping());
    }

    private static void reserveSeededEntryNames(
            Map<String, String> entryNameMap,
            Map<String, Set<String>> usedEntryNames
    ) {
        for (Map.Entry<String, String> seeded : entryNameMap.entrySet()) {
            int slash = seeded.getKey().indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String resType = seeded.getKey().substring(0, slash);
            usedEntryNames.computeIfAbsent(resType, ignored -> new HashSet<>())
                    .add(seeded.getValue());
        }
    }

    private static void reserveSeededDirectoryNames(
            Map<String, String> directoryNameMap,
            Set<String> usedDirectoryNames
    ) {
        for (String obfuscatedDirectory : directoryNameMap.values()) {
            int slash = obfuscatedDirectory.lastIndexOf('/');
            usedDirectoryNames.add(
                    slash >= 0 ? obfuscatedDirectory.substring(slash + 1) : obfuscatedDirectory
            );
        }
    }

    private static String entryKey(String resourceType, String entryName) {
        return resourceType + "/" + entryName;
    }

    static final class Plan {
        final Map<String, String> entryNameMap;
        final Map<String, String> directoryNameMap;
        final Map<String, String> filePathMap;

        private Plan(
                Map<String, String> entryNameMap,
                Map<String, String> directoryNameMap,
                Map<String, String> filePathMap
        ) {
            this.entryNameMap = entryNameMap;
            this.directoryNameMap = directoryNameMap;
            this.filePathMap = filePathMap;
        }

        boolean hasChanges() {
            return !entryNameMap.isEmpty() || !filePathMap.isEmpty();
        }
    }
}
