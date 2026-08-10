package io.github.amsonix.molt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.util.zip.ZipFile

/** feature_id / preset 级额外断言（task SUCCESS 之外）。 */
object FeatureProbeAssertions {

    fun assertSmoke(row: FeatureProbeMatrix.Row, root: File, buildOutput: String) {
        when (row.featureId) {
            "F00-baseline" -> assertDefaultBaselineSmoke(root)
            "F01-overlay-rename" -> assertOverlayRenameSmoke(root, buildOutput)
            "F02-overlay-images" -> assertOverlayImagesSmoke(root)
            "F03-overlay-noise" -> assertOverlayNoiseSmoke(root)
            "F04-junk-activity" -> assertJunkActivitySmoke(root, buildOutput)
            "F14-variant-config" -> assertVariantConfigSmoke(root)
            "F15-shrink-keep" -> assertShrinkKeepSmoke(buildOutput)
        }
    }

    fun assertAfterApk(row: FeatureProbeMatrix.Row, root: File, agpVersion: String) {
        when (row.preset) {
            "arsc-dir", "arsc-file" -> assertArscMapping(root, row.preset)
            "keep-verify" -> assertKeepVerifyApk(root, agpVersion)
            "assets-encrypt" -> assertAssetsEncrypt(root)
            "baseline-sync", "rename-full" -> if (row.featureId == "F13-baseline-sync") {
                assertBaselineSyncApk(root)
            }
        }
    }

    fun assertAfterAab(row: FeatureProbeMatrix.Row, root: File, agpVersion: String) {
        if (row.preset == "keep-verify") {
            assertKeepVerifyAab(root, agpVersion)
        }
    }

    fun assertAfterApkRename(row: FeatureProbeMatrix.Row, root: File) {
        if (row.featureId == "F13-baseline-sync") {
            assertBaselineSyncApk(root)
        }
        if (row.featureId == "F16-string-fog-assets") {
            assertStringFogAssets(root)
        }
        val shellMapping = File(root, "app/build/outputs/mapping/googleRelease/shell-obfuscate-mapping.txt")
        assertTrue("merged mapping should exist after rename APK probe", shellMapping.isFile)
    }

    private fun assertDefaultBaselineSmoke(root: File) {
        val mapping = File(root, "build/shell-obfuscate/googleRelease/component-mapping.json").readText()
        assertTrue("mapping should include library activity", mapping.contains("fixture.lib.LibraryActivity"))

        val layoutFiles = overlayLayoutFileNames(root)
        assertTrue(
            "google flavor layout should be overlayed (found: $layoutFiles)",
            layoutFiles.contains("google.xml"),
        )
        assertTrue(
            "main layout should be overlayed (found: $layoutFiles)",
            layoutFiles.contains("base.xml"),
        )
        assertFalse(
            "samsung flavor layout should stay isolated from googleRelease (found: $layoutFiles)",
            layoutFiles.contains("samsung.xml"),
        )

        val junkKeep = File(root, "app/build/shell-obfuscate/molt-junk-keep.pro").readText()
        assertTrue("junk keep rules should target fixture package", junkKeep.contains("fixture.custom.junk.**"))
    }

    private fun assertOverlayRenameSmoke(root: File, buildOutput: String) {
        assertTrue(
            "autoDiscoverKeepXml should mention library keep.xml",
            buildOutput.contains("autoDiscoverKeepXml") &&
                buildOutput.contains("library/src/main/res/raw/keep.xml"),
        )
        val generatedResRoot = File(root, "app/build/generated/shell-obfuscate/googleRelease/res")
        val renameReport = File(root, "app/build/shell-obfuscate/googleRelease/xml-rename.txt")
        val layoutFiles = overlayLayoutFileNames(root)
        assertTrue(
            "resource overlay should emit layout files " +
                "(resRoot=${generatedResRoot.isDirectory}, " +
                "xml-rename=${renameReport.isFile}, renameReport=${renameReport.takeIf { it.isFile }?.readText()?.lineSequence()?.take(5)?.joinToString()}, " +
                "found=$layoutFiles)",
            layoutFiles.isNotEmpty(),
        )
        assertTrue(
            "kept layout should stay base.xml (found: $layoutFiles)",
            layoutFiles.contains("base.xml"),
        )
        val renamedGoogle = layoutFiles.filter { it.endsWith(".xml") && it != "base.xml" }
        assertTrue("google layout should be renamed (found: $layoutFiles)", renamedGoogle.isNotEmpty())
        assertFalse(
            "google.xml should not remain when rename enabled (found: $layoutFiles)",
            layoutFiles.contains("google.xml"),
        )
    }

    /**
     * AGP 8.0 smoke 只跑 overlay 任务时，generated res 可能被 AGP 消费后不再保留；
     * 回退读取 incremental overlay cache 中各源目录产物。
     */
    private fun overlayLayoutFileNames(root: File, variant: String = "googleRelease"): List<String> {
        val generatedLayout = File(root, "app/build/generated/shell-obfuscate/$variant/res/layout")
        if (generatedLayout.isDirectory) {
            return generatedLayout.listFiles().orEmpty().filter { it.isFile }.map { it.name }
        }
        val cacheRoot = File(root, "app/build/shell-obfuscate/$variant/res-overlay-cache/dirs")
        if (!cacheRoot.isDirectory) return emptyList()
        return cacheRoot.listFiles().orEmpty()
            .flatMap { entry ->
                File(entry, "res/layout").listFiles().orEmpty().filter { it.isFile }.map { it.name }
            }
            .distinct()
    }

    private fun assertOverlayImagesSmoke(root: File) {
        val report = File(root, "app/build/shell-obfuscate/googleRelease/image-anti-detect-report.txt")
        val generatedDrawable = File(root, "app/build/generated/shell-obfuscate/googleRelease/res/drawable")
        assertTrue(
            "overlay-images should process probe assets",
            report.isFile ||
                generatedDrawable.walkTopDown().any { it.name.startsWith("probe.") },
        )
    }

    private fun assertOverlayNoiseSmoke(root: File) {
        val generatedDrawable = File(root, "app/build/generated/shell-obfuscate/googleRelease/res/drawable")
        assertTrue(
            "overlay-noise should emit processed drawable",
            generatedDrawable.walkTopDown().any { it.name.contains("probe_noise") },
        )
    }

    private fun assertJunkActivitySmoke(root: File, buildOutput: String) {
        val junkKeep = File(root, "app/build/shell-obfuscate/molt-junk-keep.pro")
        assertTrue("junk keep rules should be generated", junkKeep.isFile)
        val junkManifest = File(root, "app/build/generated/shell-obfuscate/googleRelease/junk/AndroidManifest.xml")
        assertTrue(
            "junk manifest should be generated for activity junk",
            junkManifest.isFile || buildOutput.contains("moltObfuscateJunkCodeGoogleRelease"),
        )
    }

    private fun assertVariantConfigSmoke(root: File) {
        val junkKeep = File(root, "app/build/shell-obfuscate/molt-junk-keep.pro").readText()
        assertTrue(
            "variantConfig heavy junk should expand keep rules",
            junkKeep.lines().size >= 3,
        )
    }

    private fun assertShrinkKeepSmoke(buildOutput: String) {
        assertTrue(
            "shrink keep merge should mention generateShrinkKeepXmlGoogleRelease",
            buildOutput.contains("generateShrinkKeepXmlGoogleRelease") ||
                buildOutput.contains("mergeShrinkKeepXml", ignoreCase = true),
        )
    }

    private fun assertArscMapping(root: File, preset: String) {
        val mapping = File(root, "build/shell-obfuscate/googleRelease/apk-resource/resources-mapping.txt")
        assertTrue("APK resources-mapping.txt should exist for arsc preset", mapping.isFile)
        val text = mapping.readText()
        assertTrue("resources-mapping should not be empty", text.isNotBlank())
        if (preset == "arsc-dir") {
            assertTrue("dir mode mapping expected path-like entries", text.contains("/") || text.contains("dir"))
        }
    }

    private fun assertKeepVerifyApk(root: File, agpVersion: String) {
        val apk = AgpTestFixture.findReleaseApk(root, agpVersion)
        assertTrue("APK should exist for keep-verify probe", apk != null)
        ZipFile(apk!!).use { zip ->
            val layoutEntries = zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("res/layout/") && it.endsWith(".xml") }
                .toList()
            assertTrue("APK should still contain a layout entry after keep verify", layoutEntries.isNotEmpty())
        }
    }

    private fun assertKeepVerifyAab(root: File, agpVersion: String) {
        val aab = AgpTestFixture.findReleaseAab(root, agpVersion)
        assertTrue("AAB should exist for keep-verify probe", aab != null)
    }

    private fun assertStringFogAssets(root: File) {
        val apk = File(root, "app/build").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".apk") && !it.name.startsWith("mapping-rewrite-") }
            .maxWithOrNull(compareBy(File::lastModified))
        assertTrue("transformed APK should exist for string-fog-assets probe", apk != null)
        java.util.zip.ZipFile(apk!!).use { zf ->
            val dexBytes = java.io.ByteArrayOutputStream().apply {
                zf.entries().asSequence()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .forEach { entry -> zf.getInputStream(entry).use { write(it.readBytes()) } }
            }.toByteArray()
            assertFalse(
                "plaintext marker must be encrypted out of DEX",
                dexBytes.containsBytes("molt fog probe marker".encodeToByteArray()),
            )
            val fogDescriptor =
                io.github.amsonix.molt.internal.bundle.DexStringEncryptor.fogDescriptor("fixture.app", 7)
            assertTrue(
                "Fog decryption class must be present ($fogDescriptor)",
                dexBytes.containsBytes(fogDescriptor.encodeToByteArray()),
            )
            val json = zf.getInputStream(zf.getEntry("assets/probe_config.json"))
                .use { it.readBytes().decodeToString() }
            assertTrue(
                "assets json must be perturbed (featureless junk field)",
                json != """{"api": "https://probe.example.com", "key": "v1"}""",
            )
            assertTrue(
                "seed-derived junk assets files must be injected",
                zf.entries().asSequence().any {
                    it.name.startsWith("assets/") && it.name.endsWith(".txt")
                },
            )
        }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun assertAssetsEncrypt(root: File) {
        val apk = File(root, "app/build").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".apk") && !it.name.startsWith("mapping-rewrite-") }
            .maxWithOrNull(compareBy(File::lastModified))
        assertTrue("transformed APK should exist for assets-encrypt probe", apk != null)
        java.util.zip.ZipFile(apk!!).use { zf ->
            val secret = zf.getInputStream(zf.getEntry("assets/secret.cfg")).use { it.readBytes() }
            assertFalse(
                "assets/secret.cfg must be encrypted",
                String(secret, Charsets.UTF_8).contains("token=abc123"),
            )
            val intro = zf.getInputStream(zf.getEntry("assets/intro.mp4")).use { it.readBytes() }
            assertTrue(
                "openFd-referenced media must stay plaintext (autoExcludeFdFiles)",
                String(intro, Charsets.UTF_8).contains("MP4PLAINTEXT-OPENFD"),
            )
            val dexBytes = java.io.ByteArrayOutputStream().apply {
                zf.entries().asSequence()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .forEach { entry -> zf.getInputStream(entry).use { write(it.readBytes()) } }
            }.toByteArray()
            val fogDescriptor =
                io.github.amsonix.molt.internal.bundle.FogAssetsSource.fogAssetsDescriptor("fixture.app", 7)
            assertTrue(
                "FogAssets class must be present ($fogDescriptor)",
                dexBytes.containsBytes(fogDescriptor.encodeToByteArray()),
            )
            val (fogOpenCalls, amOpenCalls, hasRead, hasMain) = analyzeOpenCalls(zf)
            assertTrue(
                "AssetManager.open must be rewritten to FogAssets.open " +
                    "(FogOpenCalls=$fogOpenCalls AssetOpenCalls=$amOpenCalls read=$hasRead main=$hasMain)",
                fogOpenCalls > 0 && amOpenCalls == 0,
            )
        }
    }

    private data class OpenCallStats(val fogOpenCalls: Int, val assetManagerOpenCalls: Int, val hasRead: Boolean, val hasMain: Boolean)

    private fun analyzeOpenCalls(zf: java.util.zip.ZipFile): OpenCallStats {
        var fogOpen = 0
        var amOpen = 0
        var hasRead = false
        var hasMain = false
        val fogDescriptor =
            io.github.amsonix.molt.internal.bundle.FogAssetsSource.fogAssetsDescriptor("fixture.app", 7)
        zf.entries().asSequence()
            .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
            .forEach { entry ->
                val bytes = zf.getInputStream(entry).use { it.readBytes() }
                hasRead = hasRead || bytes.containsBytes("read".encodeToByteArray())
                hasMain = hasMain || bytes.containsBytes("fixture/app/MainActivity".encodeToByteArray())
                val dexFile = org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                    null,
                    java.io.BufferedInputStream(java.io.ByteArrayInputStream(bytes)),
                )
                for (clazz in dexFile.classes) {
                    if (clazz.type == fogDescriptor) continue
                    for (method in clazz.virtualMethods + clazz.directMethods) {
                        val impl = method.implementation ?: continue
                        for (ins in impl.instructions) {
                            val ref = (ins as? org.jf.dexlib2.iface.instruction.ReferenceInstruction)
                                ?.reference as? org.jf.dexlib2.iface.reference.MethodReference ?: continue
                            if (ref.name != "open" || ref.returnType != "Ljava/io/InputStream;") continue
                            if (ref.parameterTypes != listOf("Ljava/lang/String;") &&
                                ref.parameterTypes != listOf("Ljava/lang/String;", "I")
                            ) {
                                continue
                            }
                            when (ref.definingClass) {
                                fogDescriptor -> fogOpen++
                                "Landroid/content/res/AssetManager;" -> amOpen++
                            }
                        }
                    }
                }
            }
        return OpenCallStats(fogOpen, amOpen, hasRead, hasMain)
    }

    private fun assertBaselineSyncApk(root: File) {
        val profmCandidates = listOf(
            File(root, "app/build/intermediates/baselineprofiles/googleRelease/baseline.profm"),
            File(root, "app/build/intermediates/binary_art_profile/googleRelease/baseline.profm"),
            File(root, "app/build/intermediates/merged_art_profile/googleRelease/baseline.profm"),
        )
        val found = profmCandidates.any { it.isFile } ||
            File(root, "app/build").walkTopDown().any { it.name == "baseline.profm" && it.isFile }
        assertTrue("baseline.profm should be produced or updated after baseline-sync probe", found)
    }
}
