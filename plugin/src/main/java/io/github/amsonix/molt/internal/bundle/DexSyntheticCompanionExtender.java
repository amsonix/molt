package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.ComponentRenameEntry;
import io.github.amsonix.molt.internal.rename.RenameMapping;

import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R8 在同包生成的 synthetic（如 newHome.e0）会访问已改名组件的 package-private 成员；
 * post-R8 需把这些伴生类挪到同一混淆包下。
 */
final class DexSyntheticCompanionExtender {

    private DexSyntheticCompanionExtender() {
    }

    static DexRewritePlan extend(DexFile dexFile, RenameMapping baseMapping) {
        return extendGlobal(Collections.singletonList(dexFile), baseMapping);
    }

    /** 跨 dex 统一扩展 mapping，避免定义 dex 已改名、引用 dex 仍指向旧类名。 */
    static DexRewritePlan extendGlobal(List<DexFile> dexFiles, RenameMapping baseMapping) {
        if (baseMapping.entries().isEmpty()) {
            return new DexRewritePlan(baseMapping, Collections.emptySet());
        }
        List<ClassDef> allClasses = new ArrayList<>();
        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                allClasses.add(classDef);
            }
        }
        Map<String, String> extra = new LinkedHashMap<>();
        Set<String> usedNames = new HashSet<>();
        Set<String> occupiedDescriptors = DexSyntheticMappingAllocator.collectClassDescriptors(allClasses);
        for (ComponentRenameEntry entry : baseMapping.entries()) {
            usedNames.add(entry.getObfuscated());
            occupiedDescriptors.add(DexDescriptorNames.dotToDescriptor(entry.getObfuscated()));
        }
        for (ClassDef classDef : allClasses) {
            String dotName = DexDescriptorNames.descriptorToDot(classDef.getType());
            String mapped = baseMapping.resolve(dotName);
            if (mapped != null) {
                usedNames.add(mapped);
                occupiedDescriptors.add(DexDescriptorNames.dotToDescriptor(mapped));
            }
        }

        Map<String, Set<String>> packageAccessGraph = DexPackageAccessGraphBuilder.build(allClasses);
        boolean changed;
        do {
            int previousSize = extra.size();
            RenameMapping currentMapping = DexSyntheticMappingAllocator.mergedMapping(baseMapping, extra);
            DexSyntheticMappingAllocator.addPackageAccessMappings(
                    allClasses,
                    packageAccessGraph,
                    currentMapping,
                    extra,
                    usedNames,
                    occupiedDescriptors
            );
            currentMapping = DexSyntheticMappingAllocator.mergedMapping(baseMapping, extra);
            for (ClassDef classDef : allClasses) {
                DexSyntheticMappingAllocator.maybeAddSyntheticMapping(
                        classDef,
                        currentMapping,
                        extra,
                        usedNames,
                        occupiedDescriptors
                );
            }
            changed = extra.size() != previousSize;
        } while (changed);

        RenameMapping effectiveMapping = DexSyntheticMappingAllocator.mergedMapping(baseMapping, extra);
        Set<String> publicClassDescriptors = DexClassPublicizeCollector.collect(
                allClasses,
                effectiveMapping
        );
        return new DexRewritePlan(effectiveMapping, publicClassDescriptors);
    }
}
