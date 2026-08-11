package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.RenameMapping;

import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * post-R8 DEX 类名改包（四大组件 + 自定义 View 共用）：
 * 每个输入 dex 完整重写为一个输出 dex，不删类、不追加 supplemental dex。
 * 重写由 {@link DexBinaryPatchWriter} 执行，所有 pool index 由 dexlib2 统一重建。
 */
public final class DexInPlaceRenameEngine {

    private DexInPlaceRenameEngine() {
    }

    public static RenameMapping buildEffectiveMapping(List<byte[]> dexBytesList, RenameMapping mapping)
            throws IOException {
        return buildRewritePlan(dexBytesList, mapping).getMapping();
    }

    public static DexRewritePlan buildRewritePlan(List<byte[]> dexBytesList, RenameMapping mapping)
            throws IOException {
        return buildRewritePlan(dexBytesList, mapping, null);
    }

    public static DexRewritePlan buildRewritePlan(
            List<byte[]> dexBytesList,
            RenameMapping mapping,
            List<String> projectPackagePrefixes
    ) throws IOException {
        try {
            return DexProjectClassScope.callWithPrefixes(
                    projectPackagePrefixes,
                    (Callable<DexRewritePlan>) () -> buildRewritePlanInternal(dexBytesList, mapping)
            );
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("post-R8 dex rewrite plan failed", e);
        }
    }

    private static DexRewritePlan buildRewritePlanInternal(
            List<byte[]> dexBytesList,
            RenameMapping mapping
    ) throws IOException {
        if (mapping.entries().isEmpty()) {
            return new DexRewritePlan(mapping, Collections.emptySet());
        }
        List<DexFile> dexFiles = new ArrayList<>(dexBytesList.size());
        for (byte[] dexBytes : dexBytesList) {
            dexFiles.add(openDex(dexBytes));
        }
        return DexSyntheticCompanionExtender.extendGlobal(dexFiles, mapping);
    }

    public static byte[] remapBytes(byte[] input, RenameMapping mapping) throws IOException {
        if (mapping.entries().isEmpty()) {
            return input;
        }
        DexFile dexFile = openDex(input);
        return remapBytes(input, mapping, DexSyntheticCompanionExtender.extend(dexFile, mapping));
    }

    public static byte[] remapBytes(byte[] input, RenameMapping mapping, RenameMapping effectiveMapping)
            throws IOException {
        DexRewritePlan plan = effectiveMapping == null
                ? null
                : new DexRewritePlan(effectiveMapping, Collections.emptySet());
        return remapBytes(input, mapping, plan);
    }

    public static byte[] remapBytes(byte[] input, RenameMapping mapping, DexRewritePlan rewritePlan)
            throws IOException {
        return remapBytes(input, mapping, rewritePlan, null);
    }

    public static byte[] remapBytes(
            byte[] input,
            RenameMapping mapping,
            DexRewritePlan rewritePlan,
            DexStringEncryptionConfig stringConfig
    ) throws IOException {
        return remapBytes(input, mapping, rewritePlan, stringConfig, null, null);
    }

    public static byte[] remapBytes(
            byte[] input,
            RenameMapping mapping,
            DexRewritePlan rewritePlan,
            DexStringEncryptionConfig stringConfig,
            DexPerturbationConfig dexPerturb
    ) throws IOException {
        return remapBytes(input, mapping, rewritePlan, stringConfig, dexPerturb, null);
    }

    public static byte[] remapBytes(
            byte[] input,
            RenameMapping mapping,
            DexRewritePlan rewritePlan,
            DexStringEncryptionConfig stringConfig,
            DexPerturbationConfig dexPerturb,
            AssetsEncryptConfig assetsEncrypt
    ) throws IOException {
        boolean needsRename = !mapping.entries().isEmpty();
        boolean needsEncrypt = stringConfig != null;
        boolean needsPerturb = dexPerturb != null;
        boolean needsAssetsEncrypt = assetsEncrypt != null;
        if (!needsRename && !needsEncrypt && !needsPerturb && !needsAssetsEncrypt) {
            return input;
        }
        DexRewritePlan plan = rewritePlan;
        if (plan == null) {
            DexFile dexFile = openDex(input);
            plan = needsRename
                    ? DexSyntheticCompanionExtender.extend(dexFile, mapping)
                    : new DexRewritePlan(mapping, Collections.emptySet());
        }
        RenameMapping rewriteMapping = plan.getMapping();
        return DexBinaryPatchWriter.patch(
                input,
                rewriteMapping,
                plan.getPublicClassDescriptors(),
                stringConfig,
                dexPerturb,
                assetsEncrypt
        );
    }

    static boolean typeNeedsRemap(String descriptor, RenameMapping mapping) {
        return DexViewRenameEngineRemap.typeNeedsRemap(descriptor, mapping);
    }

    static String remapTypeDescriptor(String descriptor, RenameMapping mapping) {
        return DexViewRenameEngineRemap.remapTypeDescriptor(descriptor, mapping);
    }

    static boolean isSelfRenamed(ClassDef classDef, RenameMapping mapping) {
        String dotName = descriptorToDot(classDef.getType());
        String mapped = mapping.resolve(dotName);
        if (mapped == null) {
            return false;
        }
        return !mapped.equals(dotName);
    }

    private static String descriptorToDot(String descriptor) {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            return descriptor;
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static DexFile openDex(byte[] input) throws IOException {
        return DexBackedDexFile.fromInputStream(
                null,
                new ByteArrayInputStream(input)
        );
    }
}
