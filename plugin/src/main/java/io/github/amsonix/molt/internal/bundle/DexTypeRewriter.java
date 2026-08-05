package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.RenameMapping;

/**
 * 等价于 dexlib2 {@code DexRewriter.getTypeRewriter} 的 type descriptor 映射。
 */
final class DexTypeRewriter {

    private final RenameMapping mapping;

    DexTypeRewriter(RenameMapping mapping) {
        this.mapping = mapping;
    }

    String rewrite(String descriptor) {
        return DexViewRenameEngineRemap.remapTypeDescriptor(descriptor, mapping);
    }

    boolean needsRewrite(String descriptor) {
        return !rewrite(descriptor).equals(descriptor);
    }
}
