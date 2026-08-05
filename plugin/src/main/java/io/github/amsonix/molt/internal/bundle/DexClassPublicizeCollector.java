package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.RenameMapping;

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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 收集改名后需提升为 public 的 package-private 类。 */
final class DexClassPublicizeCollector {

    private DexClassPublicizeCollector() {
    }

    static Set<String> collect(List<ClassDef> allClasses, RenameMapping mapping) {
        Map<String, ClassDef> classesByDescriptor = new LinkedHashMap<>();
        Map<String, Field> fieldsByKey = new LinkedHashMap<>();
        Map<String, Method> methodsByKey = new LinkedHashMap<>();
        for (ClassDef classDef : allClasses) {
            classesByDescriptor.put(classDef.getType(), classDef);
            for (Field field : classDef.getFields()) {
                fieldsByKey.put(DexDescriptorNames.fieldKey(field), field);
            }
            for (Method method : classDef.getMethods()) {
                methodsByKey.put(DexDescriptorNames.methodKey(method), method);
            }
        }

        Set<String> result = new LinkedHashSet<>();
        for (ClassDef caller : allClasses) {
            collectClassToPublicize(caller, caller.getSuperclass(), classesByDescriptor, mapping, result);
            for (String iface : caller.getInterfaces()) {
                collectClassToPublicize(caller, iface, classesByDescriptor, mapping, result);
            }
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
                        collectClassToPublicize(
                                caller,
                                ((TypeReference) reference).getType(),
                                classesByDescriptor,
                                mapping,
                                result
                        );
                    } else if (reference instanceof FieldReference) {
                        FieldReference fieldReference = (FieldReference) reference;
                        collectClassToPublicize(
                                caller,
                                fieldReference.getDefiningClass(),
                                classesByDescriptor,
                                mapping,
                                result
                        );
                        Field field = fieldsByKey.get(DexDescriptorNames.fieldKey(fieldReference));
                        verifyMemberAccessRemainsValid(
                                caller,
                                field == null ? null : field.getDefiningClass(),
                                field == null ? 0 : field.getAccessFlags(),
                                fieldReference.toString(),
                                mapping
                        );
                    } else if (reference instanceof MethodReference) {
                        MethodReference methodReference = (MethodReference) reference;
                        collectClassToPublicize(
                                caller,
                                methodReference.getDefiningClass(),
                                classesByDescriptor,
                                mapping,
                                result
                        );
                        Method method = methodsByKey.get(DexDescriptorNames.methodKey(methodReference));
                        verifyMemberAccessRemainsValid(
                                caller,
                                method == null ? null : method.getDefiningClass(),
                                method == null ? 0 : method.getAccessFlags(),
                                methodReference.toString(),
                                mapping
                        );
                    }
                }
            }
        }
        return result;
    }

    private static void collectClassToPublicize(
            ClassDef caller,
            String referencedType,
            Map<String, ClassDef> classesByDescriptor,
            RenameMapping mapping,
            Set<String> result
    ) {
        if (referencedType == null) {
            return;
        }
        String targetDescriptor = DexDescriptorNames.classDescriptor(referencedType);
        ClassDef target = classesByDescriptor.get(targetDescriptor);
        if (target == null
                || AccessFlags.PUBLIC.isSet(target.getAccessFlags())
                || !DexDescriptorNames.samePackage(caller.getType(), target.getType())) {
            return;
        }
        if (!rewrittenPackage(caller.getType(), mapping)
                .equals(rewrittenPackage(target.getType(), mapping))) {
            result.add(target.getType());
        }
    }

    private static void verifyMemberAccessRemainsValid(
            ClassDef caller,
            String definingClass,
            int accessFlags,
            String member,
            RenameMapping mapping
    ) {
        if (definingClass == null
                || !DexDescriptorNames.requiresSamePackage(accessFlags)
                || !DexDescriptorNames.samePackage(caller.getType(), definingClass)) {
            return;
        }
        if (!rewrittenPackage(caller.getType(), mapping)
                .equals(rewrittenPackage(definingClass, mapping))) {
            throw new IllegalStateException(
                    "Package-private DEX member access crosses packages after rename: "
                            + DexDescriptorNames.descriptorToDot(caller.getType()) + " -> " + member
            );
        }
    }

    private static String rewrittenPackage(String descriptor, RenameMapping mapping) {
        String original = DexDescriptorNames.descriptorToDot(descriptor);
        String mapped = mapping.resolve(original);
        return DexDescriptorNames.packageName(mapped == null ? original : mapped);
    }
}
