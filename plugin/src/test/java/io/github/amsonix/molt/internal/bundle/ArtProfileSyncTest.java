package io.github.amsonix.molt.internal.bundle;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipFile;

public class ArtProfileSyncTest {

    @Test
    public void isAabModuleDexEntry_acceptsBaseModuleDexOnly() {
        Assert.assertTrue(ArtProfileSync.isAabModuleDexEntry("base/dex/classes.dex"));
        Assert.assertTrue(ArtProfileSync.isAabModuleDexEntry("base/dex/classes2.dex"));
        Assert.assertTrue(ArtProfileSync.isAabModuleDexEntry("base/dex/classes10.dex"));
    }

    @Test
    public void isAabModuleDexEntry_rejectsEmbeddedSdkDexUnderAssets() {
        Assert.assertFalse(ArtProfileSync.isAabModuleDexEntry("base/assets/audience_network/classes.dex"));
        Assert.assertFalse(ArtProfileSync.isAabModuleDexEntry("base/assets/audience_network/classes2.dex"));
    }

    @Test
    public void isAabModuleDexEntry_rejectsApkStyleRootDex() {
        Assert.assertFalse(ArtProfileSync.isAabModuleDexEntry("classes.dex"));
        Assert.assertFalse(ArtProfileSync.isAabModuleDexEntry("classes2.dex"));
    }

    @Test
    public void syncZipInPlace_realGoogleReleaseAab_whenPresent() throws Exception {
        File moduleRoot = new File(System.getProperty("user.dir"));
        File repoRoot = moduleRoot.getParentFile().getParentFile();
        File sourceAab = new File(
                repoRoot,
                "app/build/outputs/bundle/googleRelease/app-google-release.aab"
        );
        File humanReadable = new File(
                repoRoot,
                "app/src/googleRelease/generated/baselineProfiles/baseline-prof.txt"
        );
        File mapping = new File(
                repoRoot,
                "app/build/outputs/mapping/googleRelease/shell-obfuscate-mapping.txt"
        );
        Assume.assumeTrue("googleRelease AAB not built", sourceAab.isFile());
        Assume.assumeTrue("baseline-prof.txt missing", humanReadable.isFile());
        Assume.assumeTrue("shell-obfuscate mapping missing", mapping.isFile());

        File workingCopy = File.createTempFile("shell-aab-profile-sync", ".aab");
        try {
            Files.copy(sourceAab.toPath(), workingCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            int beforeSize = readBaselineProfSize(workingCopy);
            ArtProfileSync.Result result = ArtProfileSync.syncZipInPlace(
                    workingCopy,
                    new ArtProfileSync.Config(humanReadable, mapping)
            );
            int afterSize = readBaselineProfSize(workingCopy);
            Assert.assertTrue(result.message, result.synced);
            Assert.assertTrue("baseline.prof should remain valid after post-R8 sync", afterSize > 50_000);
            Assert.assertTrue(
                    "baseline.prof should not shrink after post-R8 sync",
                    afterSize >= beforeSize
            );
        } finally {
            workingCopy.delete();
        }
    }

    private static int readBaselineProfSize(File aab) throws Exception {
        try (ZipFile zip = new ZipFile(aab)) {
            var entry = zip.getEntry(ZipArtProfilePatcher.AAB_BASELINE_PROF);
            return zip.getInputStream(entry).readAllBytes().length;
        }
    }
}
