package io.github.amsonix.molt.internal.bundle;

import com.android.aapt.Resources;
import io.github.amsonix.molt.internal.reschiper.bundle.ResourceMapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** 将 APK arsc 混淆 plan 写入 ResChiper 兼容的 resources-mapping.txt。 */
final class ApkResourceMappingWriter {

    private ApkResourceMappingWriter() {
    }

    static void write(
            Resources.ResourceTable table,
            ApkResourceTableRewriter.Plan plan,
            File outputFile
    ) throws IOException {
        ResourceMapping mapping = new ResourceMapping();
        Map<String, String> entryIds = collectEntryIds(table);
        for (Map.Entry<String, String> entry : plan.directoryNameMap.entrySet()) {
            mapping.putDirMapping(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : plan.filePathMap.entrySet()) {
            mapping.putEntryFileMapping(entry.getKey(), entry.getValue());
            String id = entryIds.get(entry.getKey());
            if (id != null) {
                mapping.addResourcePathAndId(entry.getKey(), id);
            }
        }
        for (Map.Entry<String, String> entry : plan.entryNameMap.entrySet()) {
            mapping.putApkResourceMapping(entry.getKey(), entry.getValue());
            String id = entryIds.get(entry.getKey());
            if (id != null) {
                mapping.addResourceNameAndId(entry.getKey(), id);
            }
        }
        Files.createDirectories(outputFile.getParentFile().toPath());
        mapping.writeMappingToFile(outputFile.toPath());
    }

    private static Map<String, String> collectEntryIds(Resources.ResourceTable table) {
        Map<String, String> ids = new HashMap<>();
        for (Resources.Package pkg : table.getPackageList()) {
            int packageId = pkg.getPackageId().getId();
            for (Resources.Type type : pkg.getTypeList()) {
                int typeId = type.getTypeId().getId();
                String typeName = type.getName();
                for (Resources.Entry entry : type.getEntryList()) {
                    int entryId = entry.getEntryId().getId();
                    int fullId = (packageId << 24) | (typeId << 16) | (entryId & 0xffff);
                    String formatted = String.format("0x%08x", fullId);
                    ids.put(typeName + "/" + entry.getName(), formatted);
                    for (Resources.ConfigValue configValue : entry.getConfigValueList()) {
                        if (!configValue.getValue().getItem().hasFile()) {
                            continue;
                        }
                        String path = configValue.getValue().getItem().getFile().getPath()
                                .replace('\\', '/');
                        ids.put(path, formatted);
                    }
                }
            }
        }
        return ids;
    }
}
