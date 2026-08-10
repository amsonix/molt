# Molt configuration reference

Chinese: [CONFIG.zh-CN.md](CONFIG.zh-CN.md)

All public options for the [`molt { }`](../README.md#common-configuration) extension. For day-to-day use, see the common blocks in the README; use this file for verification, `failOn*`, and image overlay details.

See each table’s “variant override” column and the summary at the end for `variantConfig` overrides.

## Top-level

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | `Boolean` | `true` | Master switch |
| `enabledBuildTypes` | `List<String>` | `release` | Apply only to listed build types; add custom types (e.g. `alpha`, `debug`) as needed |
| `seed` | `Int` | `applicationId.hashCode()` | Obfuscation seed; stable per applicationId |
| `keepXmlFiles` | `FileCollection` | empty | Extra keep.xml files merged with auto-discovery |
| `autoDiscoverKeepXml` | `Boolean` | `true` | Scan app and library `res/raw/keep.xml` |
| `mergeShrinkKeepXml` | `Boolean` | `false` | Merge keep from external shrink-resources plugin |
| `shrinkKeepRelativePath` | `String` | `generated/shrink-resources/{variant}/res/raw/keep.xml` | Shrink keep path template |
| `shrinkKeepGenerateTaskName` | `String` | `generateShrinkKeepXml{Variant}` | Shrink keep task name template |
| `verifyApkKeep` | `Boolean` | `false` | Verify kept resources after APK build |
| `failOnMissingApkKeep` | `Boolean` | `true` | Fail build when `verifyApkKeep` finds missing resources |
| `verifyBundleKeep` | `Boolean` | `false` | Verify kept resources after AAB build |
| `failOnMissingBundleKeep` | `Boolean` | `true` | Fail build when `verifyBundleKeep` finds missing resources |
| `useFirebaseArtifactVerifyBaseline` | `Boolean` | `false` | Use Firebase/google-services artifact verify baseline |
| `hookCrashlyticsMappingUpload` | `Boolean` | `true` | Hook Crashlytics upload to use merged mapping |
| `failOnCrashlyticsHookFailure` | `Boolean` | `false` | Fail when Crashlytics upload hook fails |
| `failOnReleaseMinifyDisabled` | `Boolean` | `false` | Fail when post-R8 features enabled but `minifyEnabled=false` |
| `failOnEmptyArtifactVerifyBaseline` | `Boolean` | `true` | Fail when verify enabled but baseline is empty |
| `failOnMissingShrinkKeepTask` | `Boolean` | `true` | Fail when `mergeShrinkKeepXml` enabled but task missing |
| `failOnJunkManifestMergeFailure` | `Boolean` | `true` | Fail when junk Manifest merge fails |
| `allowUnsignedOutput` | `Boolean` | `false` | Allow unsigned output (local debug only) |
| `failOnAgpToolchainMismatch` | `Boolean` | `false` | Fail when AGP version differs from plugin pin |
| `axmlStrictMode` | `Boolean` | `false` | Fail when binary layout cannot rename View class |
| `projectPackagePrefixes` | `List<String>` | derived from `applicationId` | Project package prefixes for DEX companion classes |
| `syncBaselineProfile` | `Boolean` | `true` | Recompile baseline profile from merged mapping |
| `failOnBaselineProfileSyncFailure` | `Boolean` | `true` | Fail when profile recompile fails |
| `baselineProfileHumanReadable` | `File` | variant default | Override `baseline-prof.txt` input |

## `junkCode { }`

Generates junk code in an isolated source set; compiled into DEX.

| Option | Type | Default | Description | Variant override |
|--------|------|---------|-------------|------------------|
| `enabled` | `Boolean` | `true` | Junk Code switch | ✓ |
| `profile` | `String` | `light` | Utility class scale preset | ✓ |
| `packageCount` | `Int` | `5` | Sub-package count (`custom` profile) | ✓ |
| `classCount` | `Int` | `30` | Utility class count, **excludes** Activities (`custom`) | ✓ |
| `methodsPerClass` | `Int` | `8` | Methods per class (`custom`) | ✓ |
| `activityCountPerPackage` | `Int` | `0` | Activities per sub-package; `0` = none | ✓ |
| `excludeActivityJavaFile` | `Boolean` | `false` | Skip Activity `.java`; still emit layout / Manifest snippet | ✓ |
| `mergeJunkManifest` | `Boolean` | `false` | Merge junk Activities into app Manifest (needs `activityCountPerPackage > 0`) | ✓ |
| `resPrefix` | `String` | `junk_` | Activity layout resource prefix | ✓ |
| `packagePrefix` | `String` | `{applicationId}.shell.junk` | Junk class package prefix | — |

### `profile` presets (utility classes only)

| profile | Sub-packages | Utility classes | Methods/class |
|---------|--------------|-----------------|---------------|
| `light` | 5 | 30 | 8 |
| `medium` | 10 | 100 | 12 |
| `heavy` | 30 | 1500 | 20 |
| `custom` | Uses `packageCount` / `classCount` / `methodsPerClass` | | |

## `resourceObfuscate { }`

Compile-time resource overlay (image rewrite, XML injection, etc.).

| Option | Type | Default | Description | Variant override |
|--------|------|---------|-------------|------------------|
| `enabled` | `Boolean` | `true` | Resource overlay switch | ✓ |
| `renameXmlFiles` | `Boolean` | `false` | Obfuscate XML file names | ✓ |
| `injectXmlJunk` | `Boolean` | `false` | Inject comment placeholders at end of layouts | ✓ |
| `imageAntiDetect` | `Boolean` | `true` | Rewrite image metadata at compile time | ✓ |
| `imageMicroCompress` | `Boolean` | `true` | Image micro-compress master switch | — |
| `imagePngMicroCompress` | `Boolean` | `false` | PNG micro-compress | ✓ |
| `imageJpegMicroCompress` | `Boolean` | `true` | JPEG micro-compress | ✓ |
| `imageMicroCompressQuality` | `Float` | `0.97` | Quality (0–1) | — |
| `imageJpegMetadataMode` | `String` | `both` | JPEG metadata mode: `com` / `exif` / `both` | — |
| `imagePngExtraChunks` | `Boolean` | `true` | Append PNG extra chunks | — |
| `imagePerceptualNoise` | `Boolean` | `false` | LSB noise (perceptual-hash resistance) | — |
| `verifyImageAntiDetect` | `Boolean` | `true` | Verify image rewrite during overlay | — |
| `failOnUnchangedImageAntiDetect` | `Boolean` | `true` | Fail when image unchanged | — |
| `imageAntiDetectApkFallback` | `Boolean` | `true` | APK transform-stage image metadata fallback | — |
| `verifyApkImageAntiDetect` | `Boolean` | `false` | Decode-verify all res images after APK build | — |
| `failOnApkImageAntiDetectFailure` | `Boolean` | `true` | Fail on APK image verify failure | — |
| `failOnSkippedUnsupportedImageAntiDetect` | `Boolean` | `false` | Fail when overlay skips PNG/JPEG | — |
| `imageAntiDetectBundleFallback` | `Boolean` | `true` | AAB transform-stage image metadata fallback | — |
| `verifyBundleImageAntiDetect` | `Boolean` | `false` | Decode-verify all res images after AAB build | — |
| `failOnBundleImageAntiDetectFailure` | `Boolean` | `true` | Fail on AAB image verify failure | — |
| `overlayParallelism` | `Int` | `0` | Overlay parallelism; `0` = min(4, CPUs) | — |
| `incrementalOverlay` | `Boolean` | `true` | Incremental skip by res directory fingerprint | ✓ |
| `maxWebpExtendedSkipRatio` | `Double` | `0.05` | Extended WebP skip ratio threshold; `0` = disabled | — |

## `bundleResourceObfuscate { }`

Obfuscates `resources.arsc` and `res` paths inside APK/AAB.

| Option | Type | Default | Description | Variant override |
|--------|------|---------|-------------|------------------|
| `enabled` | `Boolean` | `true` | AAB resource table obfuscation | ✓ |
| `obfuscateApk` | `Boolean` | `true` | APK resource table obfuscation | ✓ |
| `obfuscationMode` | `String` | `default` | Mode: `default` / `dir` / `file` | — |
| `mappingFile` | `File` | auto | Reusable `resources-mapping.txt` | — |
| `reuseIncrementalMapping` | `Boolean` | `true` | Reuse mapping from last transform. **Note**: with reuse enabled, changing `seed` does **not** re-roll resource names (existing entries keep their old names; only junk / component / view names follow the new seed). Delete `build/shell-obfuscate/<variant>/{apk,bundle}-resource/resources-mapping.txt` to force a full re-roll | — |

## `componentRename { }`

After R8, maps component FQCNs referenced in Manifest / layout / navigation to random short names and patches DEX.

```
com.example.app.SplashActivity  →  e3.gj1
com.example.app.MainService     →  z0.re4.hu6
```

Simple names change too — not package-only renames.

| Option | Type | Default | Description | Variant override |
|--------|------|---------|-------------|------------------|
| `enabled` | `Boolean` | `true` | Rename Activity / Service / Receiver / Provider | ✓ |
| `excludePatterns` | `List<String>` | `*.debug.*`, `*Hilt_*`, `*_HiltModules*` | FQCN globs to skip | — |

## `stringEncrypt { }`

Post-R8 DEX string encryption: `const-string` is replaced with a `Fog.d(...)` decrypt call (`const-string-jumbo` ciphertext + `invoke-static` + `move-result-object`, same register). Only strings in **project packages** (`projectPackagePrefixes`) are encrypted by default; the auto-generated `{applicationId}.shell.fog.Fog` decryption class is kept via generated ProGuard rules.

**Safety filters (built-in):** class-name FQCNs, dex descriptors (`L...;`), identifiers, and strings containing `/` (paths / URLs) are never encrypted, so reflection / Intent component names keep working. Iterate with `keepStrings` on real apps and verify at runtime.

| Option | Type | Default | Description | Variant override |
|--------|------|---------|-------------|------------------|
| `enabled` | `Boolean` | `true` | String encryption switch | ✓ |
| `excludePatterns` | `List<String>` | `*.debug.*` | Class FQCN globs to skip (matched against original names) | — |
| `keepStrings` | `List<String>` | empty | String-content regex whitelist; matched strings stay plaintext | — |

Key is derived from `seed`; identical plaintexts produce identical ciphertexts (string-pool dedup preserved). Note: with Crashlytics enabled, per-build mapping file ids make builds non-byte-reproducible (see README note 8).

## `assetsProtect { }`

Lightweight `assets/` perturbation at transform time (APK `assets/`, AAB `base/assets/`): injects a junk field into JSON objects, appends a comment to XML-like text files, and adds seed-derived junk files (`assets/molt_junk_<seed>/`). No runtime changes, no encryption — breaks content / structure fingerprints while keeping every reader working. Binary files and non-text files are never touched; JSON injection only fires on structurally valid objects (no parsing, substring-level).

| Option | Type | Default | Description | Variant override |
|--------|------|---------|-------------|------------------|
| `enabled` | `Boolean` | `false` | Assets perturbation switch | ✓ |
| `filePatterns` | `List<String>` | `*.json`, `*.txt`, `*.properties`, `*.html`, `*.js`, `*.xml` | File-name globs to perturb; patterns containing `/` match full entry paths | — |
| `junkFileCount` | `Int` | `3` | Number of injected junk files | — |
| `excludePatterns` | `List<String>` | empty | File-name / path globs to skip | — |

## `viewRename { }`

After R8, renames custom View classes in layout / navigation XML (system / AndroidX widgets unchanged).

| Option | Type | Default | Description | Variant override |
|--------|------|---------|-------------|------------------|
| `enabled` | `Boolean` | `true` | Custom View rename | ✓ |
| `excludePatterns` | `List<String>` | `*.debug.*`, `*Hilt_*`, `*_HiltModules*` | Class name globs to skip | — |
| `excludeResXmlEntryPatterns` | `List<String>` | built-in ad SDK layout rules | Layout path globs to skip | — |

## `variantConfig { create("<variant>") { } }`

Override global config per variant name (e.g. `googleRelease`). Variant name = flavor + buildType, lowercase.

```kotlin
variantConfig {
    create("googleRelease") {
        seed.set(42)
        junkCode { profile.set("heavy") }
        resourceObfuscate { imageAntiDetect.set(false) }
        bundleResourceObfuscate { obfuscateApk.set(false) }
        componentRename { enabled.set(false) }
        viewRename { enabled.set(false) }
        verify {
            verifyApkKeep.set(true)
            verifyBundleKeep.set(true)
        }
    }
}
```

| Block | Overridable options |
|-------|---------------------|
| (top-level) | `seed` |
| `junkCode` | `enabled`, `profile`, `packageCount`, `classCount`, `methodsPerClass`, `activityCountPerPackage`, `excludeActivityJavaFile`, `mergeJunkManifest`, `resPrefix` |
| `resourceObfuscate` | `enabled`, `renameXmlFiles`, `injectXmlJunk`, `imageAntiDetect`, `imagePngMicroCompress`, `imageJpegMicroCompress`, `incrementalOverlay` |
| `bundleResourceObfuscate` | `enabled`, `obfuscateApk` |
| `componentRename` | `enabled` |
| `stringEncrypt` | `enabled` |
| `assetsProtect` | `enabled` |
| `viewRename` | `enabled` |
| `verify` | `verifyApkKeep`, `verifyBundleKeep` |
