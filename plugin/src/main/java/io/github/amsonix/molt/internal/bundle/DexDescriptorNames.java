package io.github.amsonix.molt.internal.bundle;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;

/** DEX 类型描述符与包名工具。 */
final class DexDescriptorNames {

    private DexDescriptorNames() {
    }

    static String dotToDescriptor(String dotName) {
        return "L" + dotName.replace('.', '/') + ";";
    }

    static String descriptorToDot(String type) {
        if (!type.startsWith("L") || !type.endsWith(";")) {
            return type.replace('/', '.');
        }
        return type.substring(1, type.length() - 1).replace('/', '.');
    }

    static String packageName(String dotName) {
        int lastDot = dotName.lastIndexOf('.');
        return lastDot > 0 ? dotName.substring(0, lastDot) : "";
    }

    static String simpleName(String dotName) {
        int lastDot = dotName.lastIndexOf('.');
        return lastDot >= 0 ? dotName.substring(lastDot + 1) : dotName;
    }

    static String classDescriptor(String type) {
        int index = 0;
        while (index < type.length() && type.charAt(index) == '[') {
            index++;
        }
        return index < type.length() && type.charAt(index) == 'L'
                ? type.substring(index)
                : type;
    }

    static boolean samePackage(String leftDescriptor, String rightDescriptor) {
        return packageName(descriptorToDot(leftDescriptor))
                .equals(packageName(descriptorToDot(rightDescriptor)));
    }

    static boolean requiresSamePackage(int accessFlags) {
        return !AccessFlags.PUBLIC.isSet(accessFlags)
                && !AccessFlags.PRIVATE.isSet(accessFlags);
    }

    static String fieldKey(FieldReference field) {
        return field.getDefiningClass() + '\u0000' + field.getName()
                + '\u0000' + field.getType();
    }

    static String methodKey(MethodReference method) {
        StringBuilder key = new StringBuilder()
                .append(method.getDefiningClass())
                .append('\u0000')
                .append(method.getName())
                .append('\u0000');
        for (CharSequence parameterType : method.getParameterTypes()) {
            key.append(parameterType).append('\u0001');
        }
        return key.append('\u0000').append(method.getReturnType()).toString();
    }

    static boolean isProjectClass(String dotName) {
        return DexProjectClassScope.isProjectClass(dotName);
    }
}
