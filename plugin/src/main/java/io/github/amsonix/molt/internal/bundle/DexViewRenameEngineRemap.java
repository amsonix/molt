package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.RenameMapping;

/** Package-private type descriptor remap helpers. */
final class DexViewRenameEngineRemap {

    private DexViewRenameEngineRemap() {
    }

    static boolean typeNeedsRemap(String type, RenameMapping mapping) {
        return !remapTypeDescriptor(type, mapping).equals(type);
    }

    static String remapTypeDescriptor(String type, RenameMapping mapping) {
        if (type.startsWith("[")) {
            return "[" + remapTypeDescriptor(type.substring(1), mapping);
        }
        if (!type.startsWith("L") || !type.endsWith(";")) {
            return type;
        }
        String dotName = type.substring(1, type.length() - 1).replace('/', '.');
        String mapped = mapping.resolve(dotName);
        if (mapped == null) {
            return type;
        }
        return "L" + mapped.replace('.', '/') + ";";
    }
}
