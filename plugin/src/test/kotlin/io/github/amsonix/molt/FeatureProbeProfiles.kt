package io.github.amsonix.molt

import java.io.File

/** preset → TestKit fixture 补丁（在 [AgpTestFixture.writeFixture] 之后调用）。 */
object FeatureProbeProfiles {

    private const val KEEP_XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources xmlns:tools="http://schemas.android.com/tools"
            tools:keep="@layout/base" />
    """

    fun apply(root: File, preset: String) {
        when (preset) {
            "default" -> Unit
            "overlay-rename" -> applyOverlayRename(root)
            "overlay-images" -> applyOverlayImages(root)
            "overlay-noise" -> applyOverlayNoise(root)
            "junk-activity" -> applyJunkActivity(root)
            "arsc-dir" -> applyArscMode(root, "dir")
            "arsc-file" -> applyArscMode(root, "file")
            "keep-verify" -> applyKeepVerify(root)
            "baseline-sync" -> applyBaselineSync(root)
            "variant-config" -> applyVariantConfig(root)
            "shrink-keep" -> applyShrinkKeep(root)
            "rename-full" -> applyRenameFull(root)
            "string-fog-assets" -> applyStringFogAssets(root)
            "assets-encrypt" -> applyAssetsEncrypt(root)
            else -> error("Unknown feature probe preset: $preset")
        }
    }

    private fun appendMoltBlock(root: File, body: String) {
        val appGradle = File(root, "app/build.gradle")
        appGradle.writeText("${appGradle.readText()}\n\nmolt {\n$body\n}\n")
    }

    private fun replaceInAppGradle(root: File, oldValue: String, newValue: String) {
        val appGradle = File(root, "app/build.gradle")
        appGradle.writeText(appGradle.readText().replace(oldValue, newValue))
    }

    private fun applyOverlayRename(root: File) {
        AgpTestFixture.write(root, "library/src/main/res/raw/keep.xml", KEEP_XML.trimIndent())
        replaceInAppGradle(
            root,
            "resourceObfuscate.renameXmlFiles.set(false)",
            "resourceObfuscate.renameXmlFiles.set(true)",
        )
        replaceInAppGradle(
            root,
            "resourceObfuscate.injectXmlJunk.set(false)",
            "resourceObfuscate.injectXmlJunk.set(true)",
        )
    }

    private fun applyOverlayImages(root: File) {
        writeMinimalPng(root, "app/src/google/res/drawable/probe.png")
        writeMinimalJpeg(root, "app/src/google/res/drawable/probe.jpg")
        appendMoltBlock(
            root,
            """
            resourceObfuscate.imageAntiDetect.set(true)
            resourceObfuscate.imageJpegMicroCompress.set(true)
            resourceObfuscate.imagePngMicroCompress.set(true)
            """.trimIndent(),
        )
    }

    private fun applyOverlayNoise(root: File) {
        writeMinimalPng(root, "app/src/google/res/drawable/probe_noise.png")
        appendMoltBlock(root, "resourceObfuscate.imagePerceptualNoise.set(true)")
    }

    private fun applyJunkActivity(root: File) {
        appendMoltBlock(
            root,
            """
            junkCode.profile.set('heavy')
            junkCode.activityCountPerPackage.set(1)
            junkCode.mergeJunkManifest.set(true)
            """.trimIndent(),
        )
    }

    private fun applyArscMode(root: File, mode: String) {
        appendMoltBlock(root, "bundleResourceObfuscate.obfuscationMode.set('$mode')")
    }

    private fun applyKeepVerify(root: File) {
        AgpTestFixture.write(root, "app/src/main/res/raw/keep.xml", KEEP_XML.trimIndent())
        AgpTestFixture.write(root, "library/src/main/res/raw/keep.xml", KEEP_XML.trimIndent())
        appendMoltBlock(
            root,
            """
            verifyApkKeep.set(true)
            verifyBundleKeep.set(true)
            failOnEmptyArtifactVerifyBaseline.set(false)
            """.trimIndent(),
        )
    }

    private fun applyBaselineSync(root: File) {
        AgpTestFixture.write(
            root,
            "app/src/googleRelease/generated/baselineProfiles/baseline-prof.txt",
            """
            Lfixture/app/MainActivity;
            PLfixture/app/MainActivity;
            """.trimIndent(),
        )
        appendMoltBlock(
            root,
            """
            syncBaselineProfile.set(true)
            failOnBaselineProfileSyncFailure.set(true)
            """.trimIndent(),
        )
    }

    private fun applyVariantConfig(root: File) {
        appendMoltBlock(
            root,
            """
            variantConfig {
                googleRelease {
                    junkCode.profile.set('heavy')
                    verify.verifyApkKeep.set(true)
                }
            }
            failOnEmptyArtifactVerifyBaseline.set(false)
            """.trimIndent(),
        )
        AgpTestFixture.write(root, "app/src/main/res/raw/keep.xml", KEEP_XML.trimIndent())
    }

    private fun applyShrinkKeep(root: File) {
        appendMoltBlock(root, "mergeShrinkKeepXml.set(true)")
        val appGradle = File(root, "app/build.gradle")
        appGradle.writeText(
            appGradle.readText() +
                """

            tasks.register('generateShrinkKeepXmlGoogleRelease') {
                doLast {
                    def keep = file('src/googleRelease/generated/shrink-resources/googleRelease/res/raw/keep.xml')
                    keep.parentFile.mkdirs()
                    keep.text = '''<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@layout/base" />'''
                }
            }
                """.trimIndent(),
        )
    }

    private fun applyRenameFull(root: File) {
        appendMoltBlock(
            root,
            """
            syncBaselineProfile.set(false)
            allowUnsignedOutput.set(true)
            """.trimIndent(),
        )
    }

    private fun applyStringFogAssets(root: File) {
        // 标记字符串：非标识符形态才会被加密。
        AgpTestFixture.write(
            root,
            "app/src/main/java/fixture/app/MainActivity.java",
            """
            package fixture.app;
            public class MainActivity extends android.app.Activity {
                public static String marker() {
                    return "molt fog probe marker";
                }
                @Override
                protected void onCreate(android.os.Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    android.util.Log.i("MoltProbe", marker());
                }
            }
            """.trimIndent(),
        )
        AgpTestFixture.write(
            root,
            "app/src/main/assets/probe_config.json",
            """{"api": "https://probe.example.com", "key": "v1"}""",
        )
        appendMoltBlock(
            root,
            """
            stringEncrypt.enabled.set(true)
            assetsProtect.enabled.set(true)
            assetsProtect.filePatterns.set(['*.json'])
            assetsProtect.junkFileCount.set(1)
            bundleResourceObfuscate.enabled.set(true)
            bundleResourceObfuscate.obfuscateApk.set(true)
            allowUnsignedOutput.set(true)
            """.trimIndent(),
        )
        // 确保 marker()/onCreate 不被 R8 shrink：字符串必须存活才能验证加密（根治 F16 假阳性）。
        File(root, "app/proguard-rules.pro").appendText(
            "\n-keep class fixture.app.MainActivity { *; }\n",
        )
    }

    private fun applyAssetsEncrypt(root: File) {
        // 加密清单文件 + 读取它的 Activity（调用点改写目标）。
        AgpTestFixture.write(
            root,
            "app/src/main/assets/secret.cfg",
            "token=abc123\nurl=https://example.com/{id}\n",
        )
        // 媒体文件：在清单内（*.mp4）但被 openFd 消费 → 自动排除，保持明文。
        AgpTestFixture.write(
            root,
            "app/src/main/assets/intro.mp4",
            "MP4PLAINTEXT-OPENFD",
        )
        AgpTestFixture.write(
            root,
            "app/src/main/java/fixture/app/MainActivity.java",
            """
            package fixture.app;
            public class MainActivity extends android.app.Activity {
                public java.io.InputStream read() throws java.io.IOException {
                    return getAssets().open("secret.cfg");
                }

                public java.io.InputStream read2() throws java.io.IOException {
                    return getAssets().open("secret.cfg", 0);
                }

                public android.content.res.AssetFileDescriptor readFd() throws java.io.IOException {
                    return getAssets().openFd("intro.mp4");
                }

                @Override
                protected void onCreate(android.os.Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    try {
                        String a = new String(streamBytes(read()), "UTF-8");
                        String b = new String(streamBytes(read2()), "UTF-8");
                        android.util.Log.i("MoltProbe", "molt fog probe marker 1arg=" + a.trim() + " 2arg=" + b.trim());
                        android.content.res.AssetFileDescriptor afd = readFd();
                        String c = new String(fdBytes(afd), "UTF-8");
                        afd.close();
                        android.util.Log.i("MoltProbe", "molt fog probe marker fd=" + c.trim());
                    } catch (Exception e) {
                        android.util.Log.e("MoltProbe", "fog probe exception", e);
                        throw new RuntimeException("fog probe failed", e);
                    }
                }

                private static byte[] fdBytes(android.content.res.AssetFileDescriptor afd) throws java.io.IOException {
                    java.io.FileInputStream fis = new java.io.FileInputStream(afd.getFileDescriptor());
                    try {
                        fis.skip(afd.getStartOffset());
                        byte[] buf = new byte[(int) afd.getLength()];
                        int off = 0;
                        while (off < buf.length) {
                            int n = fis.read(buf, off, buf.length - off);
                            if (n < 0) break;
                            off += n;
                        }
                        return buf;
                    } finally {
                        fis.close();
                    }
                }

                private static byte[] streamBytes(java.io.InputStream in) throws java.io.IOException {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[256];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                }
            }
            """.trimIndent(),
        )
        appendMoltBlock(
            root,
            """
            stringEncrypt.enabled.set(false)
            assetsEncrypt.enabled.set(true)
            assetsEncrypt.filePatterns.set(['*.cfg', '*.mp4'])
            bundleResourceObfuscate.enabled.set(true)
            bundleResourceObfuscate.obfuscateApk.set(true)
            allowUnsignedOutput.set(true)
            """.trimIndent(),
        )
        // read() 必须存活：调用点改写才有目标。
        File(root, "app/proguard-rules.pro").appendText("\n-keep class fixture.app.MainActivity { *; }\n")
    }

    private fun writeMinimalPng(root: File, relativePath: String) {
        val target = File(root, relativePath)
        target.parentFile.mkdirs()
        target.writeBytes(MINIMAL_PNG)
    }

    private fun writeMinimalJpeg(root: File, relativePath: String) {
        val target = File(root, relativePath)
        target.parentFile.mkdirs()
        target.writeBytes(MINIMAL_JPEG)
    }

    /** 1×1 PNG（RGBA）。 */
    private val MINIMAL_PNG: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
        0x42, 0x60, 0x82.toByte(),
    )

    /** 1×1 JPEG。 */
    private val MINIMAL_JPEG: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43,
        0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07,
        0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B,
        0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F,
        0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20,
        0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30,
        0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32,
        0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF.toByte(), 0xC0.toByte(), 0x00,
        0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05,
        0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
        0x07, 0x08, 0x09, 0x0A, 0x0B, 0xFF.toByte(), 0xC4.toByte(), 0x00,
        0xB5.toByte(), 0x10, 0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04,
        0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D, 0x01,
        0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41,
        0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81.toByte(),
        0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(),
        0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(), 0x24, 0x33, 0x62, 0x72,
        0x82.toByte(), 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25,
        0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38,
        0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
        0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64,
        0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76,
        0x77, 0x78, 0x79, 0x7A, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(),
        0x86.toByte(), 0x87.toByte(), 0x88.toByte(), 0x89.toByte(), 0x8A.toByte(),
        0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(),
        0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(),
        0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(),
        0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(),
        0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(),
        0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(),
        0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(),
        0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(),
        0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(),
        0xE1.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(),
        0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(),
        0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(), 0xF5.toByte(),
        0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(),
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00,
        0x3F, 0x00, 0xFB.toByte(), 0xD5.toByte(), 0xDB.toByte(), 0x20, 0xA8.toByte(),
        0xF1.toByte(), 0x7E, 0xFF.toByte(), 0xD9.toByte(),
    )
}
