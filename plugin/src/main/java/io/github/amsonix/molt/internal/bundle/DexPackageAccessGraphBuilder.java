package io.github.amsonix.molt.internal.bundle;

import org.jf.dexlib2.AccessFlags;
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

/** 构建 package-private 成员必须同包的 DEX 类连通图。 */
final class DexPackageAccessGraphBuilder {

    private DexPackageAccessGraphBuilder() {
    }

    static Map<String, Set<String>> build(List<ClassDef> allClasses) {
        Map<String, ClassDef> classesByDescriptor = new LinkedHashMap<>();
        Map<String, Field> fieldsByKey = new LinkedHashMap<>();
        Map<String, Method> methodsByKey = new LinkedHashMap<>();
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (ClassDef classDef : allClasses) {
            classesByDescriptor.put(classDef.getType(), classDef);
            graph.put(classDef.getType(), new LinkedHashSet<>());
            for (Field field : classDef.getFields()) {
                fieldsByKey.put(DexDescriptorNames.fieldKey(field), field);
            }
            for (Method method : classDef.getMethods()) {
                methodsByKey.put(DexDescriptorNames.methodKey(method), method);
            }
        }

        for (ClassDef caller : allClasses) {
            connectClassAccessIfRequired(
                    caller,
                    caller.getSuperclass(),
                    classesByDescriptor,
                    graph
            );
            for (String iface : caller.getInterfaces()) {
                connectClassAccessIfRequired(caller, iface, classesByDescriptor, graph);
            }
            collectPackageAccessEdges(
                    caller,
                    classesByDescriptor,
                    fieldsByKey,
                    methodsByKey,
                    graph
            );
        }
        return graph;
    }

    static List<String> collectComponent(
            String start,
            Map<String, Set<String>> graph,
            Set<String> visited
    ) {
        List<String> component = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            component.add(current);
            List<String> neighbors = new ArrayList<>(
                    graph.getOrDefault(current, Collections.emptySet())
            );
            Collections.sort(neighbors);
            for (String neighbor : neighbors) {
                if (visited.add(neighbor)) {
                    pending.add(neighbor);
                }
            }
        }
        Collections.sort(component);
        return component;
    }

    private static void collectPackageAccessEdges(
            ClassDef caller,
            Map<String, ClassDef> classesByDescriptor,
            Map<String, Field> fieldsByKey,
            Map<String, Method> methodsByKey,
            Map<String, Set<String>> graph
    ) {
        for (Method callerMethod : caller.getMethods()) {
            MethodImplementation implementation = callerMethod.getImplementation();
            if (implementation == null) {
                continue;
            }
            for (Instruction instruction : implementation.getInstructions()) {
                if (!(instruction instanceof ReferenceInstruction)) {
                    continue;
                }
                Reference reference = ((ReferenceInstruction) instruction).getReference();
                if (reference instanceof TypeReference) {
                    connectClassAccessIfRequired(
                            caller,
                            ((TypeReference) reference).getType(),
                            classesByDescriptor,
                            graph
                    );
                } else if (reference instanceof FieldReference) {
                    FieldReference fieldReference = (FieldReference) reference;
                    connectClassAccessIfRequired(
                            caller,
                            fieldReference.getDefiningClass(),
                            classesByDescriptor,
                            graph
                    );
                    Field field = fieldsByKey.get(DexDescriptorNames.fieldKey(fieldReference));
                    if (field != null
                            && DexDescriptorNames.requiresSamePackage(field.getAccessFlags())
                            && DexDescriptorNames.samePackage(caller.getType(), field.getDefiningClass())) {
                        connect(graph, caller.getType(), field.getDefiningClass());
                    }
                } else if (reference instanceof MethodReference) {
                    MethodReference methodReference = (MethodReference) reference;
                    connectClassAccessIfRequired(
                            caller,
                            methodReference.getDefiningClass(),
                            classesByDescriptor,
                            graph
                    );
                    Method method = methodsByKey.get(DexDescriptorNames.methodKey(methodReference));
                    if (method != null
                            && DexDescriptorNames.requiresSamePackage(method.getAccessFlags())
                            && DexDescriptorNames.samePackage(caller.getType(), method.getDefiningClass())) {
                        connect(graph, caller.getType(), method.getDefiningClass());
                    }
                }
            }
        }
    }

    private static void connectClassAccessIfRequired(
            ClassDef caller,
            String referencedType,
            Map<String, ClassDef> classesByDescriptor,
            Map<String, Set<String>> graph
    ) {
        if (referencedType == null) {
            return;
        }
        String targetDescriptor = DexDescriptorNames.classDescriptor(referencedType);
        ClassDef target = classesByDescriptor.get(targetDescriptor);
        if (target != null
                && !AccessFlags.PUBLIC.isSet(target.getAccessFlags())
                && DexDescriptorNames.samePackage(caller.getType(), target.getType())) {
            connect(graph, caller.getType(), target.getType());
        }
    }

    private static void connect(Map<String, Set<String>> graph, String left, String right) {
        if (left.equals(right)) {
            return;
        }
        graph.computeIfAbsent(left, ignored -> new LinkedHashSet<>()).add(right);
        graph.computeIfAbsent(right, ignored -> new LinkedHashSet<>()).add(left);
    }
}
