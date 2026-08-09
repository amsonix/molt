package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.RenameMapping;

import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/**
 * Rewrites one input dex into one output dex with dexlib2.
 *
 * <p>Renaming a descriptor can change the lexical order of both string_ids and type_ids.
 * DexPool rebuilds the complete reference graph so all indexes remain consistent and sorted.
 */
final class DexBinaryPatchWriter {

    private DexBinaryPatchWriter() {
    }

    static byte[] patch(byte[] input, RenameMapping mapping) throws IOException {
        return patch(input, mapping, Collections.emptySet(), null);
    }

    static byte[] patch(
            byte[] input,
            RenameMapping mapping,
            Set<String> publicClassDescriptors
    ) throws IOException {
        return patch(input, mapping, publicClassDescriptors, null);
    }

    static byte[] patch(
            byte[] input,
            RenameMapping mapping,
            Set<String> publicClassDescriptors,
            DexStringEncryptionConfig stringConfig
    ) throws IOException {
        if (mapping.entries().isEmpty()
                && publicClassDescriptors.isEmpty()
                && stringConfig == null) {
            return input;
        }

        DexBackedDexFile dexFile = openDex(input);
        DexTypeRewriter typeRewriter = new DexTypeRewriter(mapping);
        if (!anyTypeNeedsRewrite(dexFile, typeRewriter)
                && !containsClassToPublicize(dexFile, publicClassDescriptors)
                && !containsEncryptableClass(dexFile, stringConfig)) {
            return input;
        }

        RewriterModule module = new RewriterModule() {
            @Override
            public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return typeRewriter::rewrite;
            }
        };
        DexRewriter dexRewriter = new DexRewriter(module);
        DexPool pool = new DexPool(dexFile.getOpcodes());
        for (ClassDef classDef : dexFile.getClasses()) {
            ClassDef rewritten = dexRewriter.getClassDefRewriter().rewrite(classDef);
            if (publicClassDescriptors.contains(classDef.getType())
                    && !AccessFlags.PUBLIC.isSet(rewritten.getAccessFlags())) {
                rewritten = publicClassDef(rewritten);
            }
            if (stringConfig != null
                    && DexStringEncryptor.INSTANCE.shouldEncryptClass(classDef.getType(), stringConfig)) {
                rewritten = DexStringEncryptor.INSTANCE.rewriteClassStrings(rewritten, stringConfig);
            }
            pool.internClass(rewritten);
        }

        MemoryDataStore store = new MemoryDataStore();
        pool.writeTo(store);
        return store.getData();
    }

    private static boolean containsEncryptableClass(
            DexBackedDexFile dexFile,
            DexStringEncryptionConfig stringConfig
    ) {
        if (stringConfig == null) {
            return false;
        }
        for (ClassDef classDef : dexFile.getClasses()) {
            if (DexStringEncryptor.INSTANCE.shouldEncryptClass(classDef.getType(), stringConfig)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyTypeNeedsRewrite(
            DexBackedDexFile dexFile,
            DexTypeRewriter typeRewriter
    ) {
        for (int index = 0; index < dexFile.getTypeSection().size(); index++) {
            if (typeRewriter.needsRewrite(dexFile.getTypeSection().get(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsClassToPublicize(
            DexBackedDexFile dexFile,
            Set<String> publicClassDescriptors
    ) {
        if (publicClassDescriptors.isEmpty()) {
            return false;
        }
        for (ClassDef classDef : dexFile.getClasses()) {
            if (publicClassDescriptors.contains(classDef.getType())
                    && !AccessFlags.PUBLIC.isSet(classDef.getAccessFlags())) {
                return true;
            }
        }
        return false;
    }

    private static ClassDef publicClassDef(ClassDef classDef) {
        return new ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags() | AccessFlags.PUBLIC.getValue(),
                classDef.getSuperclass(),
                classDef.getInterfaces(),
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                classDef.getStaticFields(),
                classDef.getInstanceFields(),
                classDef.getDirectMethods(),
                classDef.getVirtualMethods()
        );
    }

    private static DexBackedDexFile openDex(byte[] input) throws IOException {
        return (DexBackedDexFile) DexBackedDexFile.fromInputStream(
                null,
                new ByteArrayInputStream(input)
        );
    }
}
