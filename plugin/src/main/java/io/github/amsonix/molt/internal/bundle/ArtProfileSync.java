package io.github.amsonix.molt.internal.bundle;

import com.android.builder.packaging.DexFileNameSupplier;
import com.android.tools.profgen.ArtProfile;
import com.android.tools.profgen.ArtProfileKt;
import com.android.tools.profgen.ArtProfileSerializer;
import com.android.tools.profgen.Diagnostics;
import com.android.tools.profgen.DexDataKt;
import com.android.tools.profgen.DexFile;
import com.android.tools.profgen.HumanReadableProfile;
import com.android.tools.profgen.HumanReadableProfileKt;
import com.android.tools.profgen.ObfuscationMap;
import com.android.tools.profgen.ObfuscationMapKt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** post-R8 DEX 改写后，用 AGP 内置 profgen 按 HRF + 最终 dex + 混淆 mapping 重编 baseline.prof。 */
public final class ArtProfileSync {

    private static final Logger LOGGER = Logger.getLogger(ArtProfileSync.class.getName());

    private ArtProfileSync() {
    }

    public static final class Config {
        public final File humanReadableProfile;
        public final File obfuscationMapping;
        public final String packageName;

        public Config(File humanReadableProfile, File obfuscationMapping) {
            this(humanReadableProfile, obfuscationMapping, "base");
        }

        public Config(File humanReadableProfile, File obfuscationMapping, String packageName) {
            this.humanReadableProfile = humanReadableProfile;
            this.obfuscationMapping = obfuscationMapping;
            this.packageName = packageName == null ? "base" : packageName;
        }
    }

    public static final class CompiledProfile {
        public final byte[] baselineProf;
        public final byte[] baselineProfm;

        public CompiledProfile(byte[] baselineProf, byte[] baselineProfm) {
            this.baselineProf = baselineProf;
            this.baselineProfm = baselineProfm;
        }
    }

    public static final class Result {
        public final boolean synced;
        public final String message;

        public Result(boolean synced, String message) {
            this.synced = synced;
            this.message = message;
        }
    }

    public static Result syncZipInPlace(File zipFile, Config config) throws IOException {
        if (!zipFile.isFile()) {
            throw new IllegalArgumentException("zip not found: " + zipFile.getPath());
        }
        if (config.humanReadableProfile == null || !config.humanReadableProfile.isFile()) {
            return new Result(false, "baseline-prof.txt missing, skip profile sync");
        }
        if (!zipContainsProfileEntries(zipFile)) {
            return new Result(false, "artifact has no baseline.prof entry, skip profile sync");
        }
        CompiledProfile compiled = compileProfile(zipFile, config);
        if (compiled == null) {
            return new Result(false, "failed to compile baseline profile from HRF");
        }
        ZipArtProfilePatcher.patchInPlace(zipFile, compiled.baselineProf, compiled.baselineProfm);
        return new Result(true, "baseline profile recompiled (" + compiled.baselineProf.length + " bytes prof)");
    }

    public static CompiledProfile compileProfile(File zipFile, Config config) {
        Diagnostics diagnostics = message ->
                LOGGER.warning("ArtProfileSync: profgen diagnostic=" + message);
        HumanReadableProfile humanReadable = HumanReadableProfileKt.HumanReadableProfile(
                config.humanReadableProfile,
                diagnostics
        );
        if (humanReadable == null) {
            LOGGER.warning("ArtProfileSync: unable to parse " + config.humanReadableProfile.getPath());
            return null;
        }
        ObfuscationMap obfuscationMap;
        try {
            obfuscationMap = loadObfuscationMap(config.obfuscationMapping);
        } catch (IOException exception) {
            LOGGER.warning("ArtProfileSync: mapping load failed error=" + exception.getMessage());
            return null;
        }
        ArtProfile profile = compileArtProfile(zipFile, humanReadable, obfuscationMap, config.packageName);
        if (profile == null) {
            return null;
        }
        try {
            return new CompiledProfile(
                    save(profile, ArtProfileSerializer.V0_1_0_P),
                    save(profile, ArtProfileSerializer.METADATA_0_0_2)
            );
        } catch (IOException exception) {
            LOGGER.warning("ArtProfileSync: save failed error=" + exception.getMessage());
            return null;
        }
    }

    private static ArtProfile compileArtProfile(
            File zipFile,
            HumanReadableProfile humanReadable,
            ObfuscationMap obfuscationMap,
            String packageName
    ) {
        try {
            if (isAabLayout(zipFile)) {
                List<DexFile> dexFiles = loadDexFilesFromZip(zipFile);
                if (dexFiles.isEmpty()) {
                    throw new IllegalStateException("no dex entries found in " + zipFile.getName());
                }
                return ArtProfileKt.ArtProfile(humanReadable, obfuscationMap, dexFiles, packageName);
            }
            return ArtProfileKt.ArtProfile(
                    humanReadable,
                    obfuscationMap,
                    DexDataKt.Apk(zipFile, zipFile.getName())
            );
        } catch (Exception error) {
            LOGGER.warning("ArtProfileSync: compile failed error=" + error.getMessage());
            return null;
        }
    }

    private static ObfuscationMap loadObfuscationMap(File mappingFile) throws IOException {
        if (mappingFile != null && mappingFile.isFile()) {
            return ObfuscationMapKt.ObfuscationMap(mappingFile);
        }
        return ObfuscationMap.Companion.getEmpty();
    }

    /** AAB 模块 dex 目录；base/assets 下内嵌 SDK 的 classes*.dex 不可参与 prof 编译。 */
    static final String AAB_MODULE_DEX_PREFIX = "base/dex/";

    private static List<DexFile> loadDexFilesFromZip(File zipFile) throws IOException {
        DexFileNameSupplier dexNameSupplier = new DexFileNameSupplier();
        List<ZipEntry> dexEntries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            zip.stream()
                    .filter(entry -> isAabModuleDexEntry(entry.getName()))
                    .sorted(Comparator.comparingInt(entry -> dexSortKey(entry.getName())))
                    .forEach(dexEntries::add);
            List<DexFile> dexFiles = new ArrayList<>(dexEntries.size());
            for (ZipEntry entry : dexEntries) {
                try (var input = zip.getInputStream(entry)) {
                    dexFiles.add(DexDataKt.DexFile(input, dexNameSupplier.get()));
                }
            }
            return dexFiles;
        }
    }

    private static boolean isAabLayout(File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            return zip.getEntry("base/dex/classes.dex") != null
                    || zip.getEntry(ZipArtProfilePatcher.AAB_BASELINE_PROF) != null;
        }
    }

    private static boolean zipContainsProfileEntries(File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            return zip.getEntry(ZipArtProfilePatcher.APK_BASELINE_PROF) != null
                    || zip.getEntry(ZipArtProfilePatcher.AAB_BASELINE_PROF) != null;
        }
    }

    /**
     * 仅匹配 AAB base 模块 dex（base/dex/classes*.dex）。
     * ponytail: 不用通配路径匹配，否则会误收 base/assets 下 SDK 内嵌 dex。
     */
    static boolean isAabModuleDexEntry(String entryName) {
        if (!entryName.startsWith(AAB_MODULE_DEX_PREFIX)) {
            return false;
        }
        String name = entryName.substring(AAB_MODULE_DEX_PREFIX.length());
        return "classes.dex".equals(name) || name.matches("classes\\d+\\.dex");
    }

    private static int dexSortKey(String entryName) {
        String name = entryName.substring(entryName.lastIndexOf('/') + 1);
        if ("classes.dex".equals(name)) {
            return 0;
        }
        if (!name.startsWith("classes") || !name.endsWith(".dex")) {
            return Integer.MAX_VALUE;
        }
        String suffix = name.substring("classes".length(), name.length() - ".dex".length());
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static byte[] save(ArtProfile profile, ArtProfileSerializer serializer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        profile.save(output, serializer);
        return output.toByteArray();
    }
}
