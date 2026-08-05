package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.ComponentRenameEntry;
import io.github.amsonix.molt.internal.rename.RenameMapping;

import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.TypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 扩展 synthetic 伴生类 mapping，保证 package-private 访问仍合法。 */
final class DexSyntheticMappingAllocator {

    private static final Pattern R8_SYNTHETIC_SIMPLE_NAME = Pattern.compile("^[a-z][a-z0-9]{0,3}$");

    private DexSyntheticMappingAllocator() {
    }

    static void addPackageAccessMappings(
            List<ClassDef> allClasses,
            Map<String, Set<String>> graph,
            RenameMapping currentMapping,
            Map<String, String> extra,
            Set<String> usedNames,
            Set<String> occupiedDescriptors
    ) {
        Set<String> visited = new HashSet<>();
        List<String> nodes = new ArrayList<>(graph.keySet());
        Collections.sort(nodes);
        for (String start : nodes) {
            if (!visited.add(start)) {
                continue;
            }
            List<String> component = DexPackageAccessGraphBuilder.collectComponent(start, graph, visited);
            Map<String, List<String>> targetPackages = new LinkedHashMap<>();
            for (String descriptor : component) {
                String original = DexDescriptorNames.descriptorToDot(descriptor);
                String mapped = resolveWithExtra(original, currentMapping, extra);
                if (mapped != null) {
                    targetPackages.computeIfAbsent(DexDescriptorNames.packageName(mapped), ignored -> new ArrayList<>())
                            .add(original + " -> " + mapped);
                }
            }
            if (targetPackages.isEmpty() || targetPackages.size() > 1) {
                continue;
            }
            String targetPackage = targetPackages.keySet().iterator().next();
            for (String descriptor : component) {
                String original = DexDescriptorNames.descriptorToDot(descriptor);
                if (resolveWithExtra(original, currentMapping, extra) != null
                        || DexDescriptorNames.packageName(original).equals(targetPackage)) {
                    continue;
                }
                String candidate = targetPackage + "." + DexDescriptorNames.simpleName(original);
                addExtraMapping(
                        original,
                        candidate,
                        allClasses,
                        currentMapping,
                        extra,
                        usedNames,
                        occupiedDescriptors
                );
            }
        }
    }

    static void maybeAddSyntheticMapping(
            ClassDef classDef,
            RenameMapping baseMapping,
            Map<String, String> extra,
            Set<String> usedNames,
            Set<String> occupiedDescriptors
    ) {
        String dotName = DexDescriptorNames.descriptorToDot(classDef.getType());
        if (baseMapping.resolve(dotName) != null || extra.containsKey(dotName)) {
            return;
        }
        if (!DexDescriptorNames.isProjectClass(dotName)) {
            return;
        }
        String simpleName = DexDescriptorNames.simpleName(dotName);
        if (simpleName.indexOf('$') >= 0 || !R8_SYNTHETIC_SIMPLE_NAME.matcher(simpleName).matches()) {
            return;
        }
        String pkg = DexDescriptorNames.packageName(dotName);
        if (!packageHasMappedClass(baseMapping, pkg)) {
            return;
        }
        String targetPkg = resolveTargetPackage(classDef, baseMapping, pkg);
        if (targetPkg == null) {
            return;
        }
        String candidate = targetPkg + "." + simpleName;
        String candidateDescriptor = DexDescriptorNames.dotToDescriptor(candidate);
        if (usedNames.contains(candidate)
                || baseMapping.resolve(candidate) != null
                || (occupiedDescriptors.contains(candidateDescriptor)
                && !candidateDescriptor.equals(classDef.getType()))) {
            return;
        }
        extra.put(dotName, candidate);
        usedNames.add(candidate);
        occupiedDescriptors.add(candidateDescriptor);
    }

    static RenameMapping mergedMapping(RenameMapping baseMapping, Map<String, String> extra) {
        if (extra.isEmpty()) {
            return baseMapping;
        }
        return baseMapping.mergedWith(RenameMapping.fromForward(extra));
    }

    static Set<String> collectClassDescriptors(List<ClassDef> classDefs) {
        Set<String> descriptors = new HashSet<>();
        for (ClassDef classDef : classDefs) {
            descriptors.add(classDef.getType());
        }
        return descriptors;
    }

    private static void addExtraMapping(
            String original,
            String candidate,
            List<ClassDef> allClasses,
            RenameMapping currentMapping,
            Map<String, String> extra,
            Set<String> usedNames,
            Set<String> occupiedDescriptors
    ) {
        String originalDescriptor = DexDescriptorNames.dotToDescriptor(original);
        String candidateDescriptor = DexDescriptorNames.dotToDescriptor(candidate);
        if (usedNames.contains(candidate)
                || resolveWithExtra(candidate, currentMapping, extra) != null
                || (occupiedDescriptors.contains(candidateDescriptor)
                && !candidateDescriptor.equals(originalDescriptor))) {
            throw new IllegalStateException(
                    "Cannot preserve package-private DEX access because target class is occupied: "
                            + original + " -> " + candidate
            );
        }
        extra.put(original, candidate);
        usedNames.add(candidate);
        occupiedDescriptors.add(candidateDescriptor);
        reserveImplicitInnerTargets(
                original,
                candidate,
                allClasses,
                currentMapping,
                extra,
                usedNames,
                occupiedDescriptors
        );
    }

    private static void reserveImplicitInnerTargets(
            String original,
            String candidate,
            List<ClassDef> allClasses,
            RenameMapping currentMapping,
            Map<String, String> extra,
            Set<String> usedNames,
            Set<String> occupiedDescriptors
    ) {
        String innerPrefix = original + "$";
        for (ClassDef classDef : allClasses) {
            String innerOriginal = DexDescriptorNames.descriptorToDot(classDef.getType());
            if (!innerOriginal.startsWith(innerPrefix)) {
                continue;
            }
            String innerCandidate = candidate + innerOriginal.substring(original.length());
            String innerCandidateDescriptor = DexDescriptorNames.dotToDescriptor(innerCandidate);
            boolean alreadyResolvedHere = innerCandidate.equals(
                    resolveWithExtra(innerOriginal, currentMapping, extra)
            );
            if ((usedNames.contains(innerCandidate) && !alreadyResolvedHere)
                    || (occupiedDescriptors.contains(innerCandidateDescriptor)
                    && !innerCandidateDescriptor.equals(classDef.getType())
                    && !alreadyResolvedHere)) {
                throw new IllegalStateException(
                        "Cannot preserve package-private DEX access because implicit inner target "
                                + "is occupied: " + innerOriginal + " -> " + innerCandidate
                );
            }
            usedNames.add(innerCandidate);
            occupiedDescriptors.add(innerCandidateDescriptor);
        }
    }

    private static String resolveWithExtra(
            String original,
            RenameMapping currentMapping,
            Map<String, String> extra
    ) {
        String exact = extra.get(original);
        if (exact != null) {
            return exact;
        }
        String mapped = currentMapping.resolve(original);
        if (mapped != null) {
            return mapped;
        }
        int dollar = original.indexOf('$');
        if (dollar <= 0) {
            return null;
        }
        String outer = original.substring(0, dollar);
        String outerMapped = extra.get(outer);
        return outerMapped != null ? outerMapped + original.substring(dollar) : null;
    }

    private static String resolveTargetPackage(ClassDef classDef, RenameMapping baseMapping, String originalPkg) {
        String referencedMapped = findReferencedMappedOriginal(classDef, baseMapping);
        if (referencedMapped != null) {
            String obfuscatedHost = baseMapping.resolve(referencedMapped);
            if (obfuscatedHost != null) {
                return DexDescriptorNames.packageName(obfuscatedHost);
            }
        }
        return defaultObfuscatedPackage(baseMapping, originalPkg);
    }

    private static String defaultObfuscatedPackage(RenameMapping mapping, String originalPkg) {
        String chosen = null;
        for (ComponentRenameEntry entry : mapping.entries()) {
            if (!DexDescriptorNames.packageName(entry.getOriginal()).equals(originalPkg)) {
                continue;
            }
            String obfuscatedPkg = DexDescriptorNames.packageName(entry.getObfuscated());
            if (chosen == null || obfuscatedPkg.compareTo(chosen) < 0) {
                chosen = obfuscatedPkg;
            }
        }
        return chosen;
    }

    private static boolean packageHasMappedClass(RenameMapping mapping, String pkg) {
        for (ComponentRenameEntry entry : mapping.entries()) {
            if (DexDescriptorNames.packageName(entry.getOriginal()).equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String findReferencedMappedOriginal(ClassDef classDef, RenameMapping mapping) {
        Set<String> referencedTypes = collectReferencedTypes(classDef);
        for (String referenced : referencedTypes) {
            String original = stripInnerSuffix(DexDescriptorNames.descriptorToDot(referenced));
            if (mapping.resolve(original) != null) {
                return original;
            }
        }
        return null;
    }

    private static String stripInnerSuffix(String dotName) {
        int dollar = dotName.indexOf('$');
        return dollar > 0 ? dotName.substring(0, dollar) : dotName;
    }

    private static Set<String> collectReferencedTypes(ClassDef classDef) {
        Set<String> types = new HashSet<>();
        String superType = classDef.getSuperclass();
        if (superType != null) {
            types.add(superType);
        }
        for (String iface : classDef.getInterfaces()) {
            types.add(iface);
        }
        for (Field field : classDef.getFields()) {
            types.add(field.getType());
        }
        for (Method method : classDef.getMethods()) {
            if (method.getReturnType() != null) {
                types.add(method.getReturnType());
            }
            for (CharSequence parameterType : method.getParameterTypes()) {
                types.add(parameterType.toString());
            }
            MethodImplementation implementation = method.getImplementation();
            if (implementation == null) {
                continue;
            }
            for (Instruction instruction : implementation.getInstructions()) {
                if (!(instruction instanceof ReferenceInstruction)) {
                    continue;
                }
                Reference reference = ((ReferenceInstruction) instruction).getReference();
                if (reference instanceof TypeReference) {
                    types.add(((TypeReference) reference).getType());
                } else if (reference instanceof FieldReference) {
                    FieldReference fieldReference = (FieldReference) reference;
                    types.add(fieldReference.getDefiningClass());
                    types.add(fieldReference.getType());
                } else if (reference instanceof MethodReference) {
                    MethodReference methodReference = (MethodReference) reference;
                    types.add(methodReference.getDefiningClass());
                    types.add(methodReference.getReturnType());
                    for (CharSequence parameterType : methodReference.getParameterTypes()) {
                        types.add(parameterType.toString());
                    }
                }
            }
        }
        return types;
    }
}
