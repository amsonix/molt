# Molt Gradle Plugin

Build-time obfuscation for Android **multi-package / white-label** apps: inject junk code, rewrite resources and DEX during normal `assemble` / `bundle`, and emit a merged mapping file. **No extra Gradle tasks required.**

中文文档：[README.zh-CN.md](README.zh-CN.md)

| | |
|---|---|
| Plugin ID | `io.github.amsonix.molt` |
| Extension | `molt { }` |
| Version | `1.0.0` |
| Requirements | AGP 8.13.x · JDK 17+ · Release with `isMinifyEnabled` |

## What it does

| Feature | Description |
|---------|-------------|
| Junk Code | Generates [utility classes](#glossary) to vary DEX (`light` / `medium` / `heavy`); optional Activities require `activityCountPerPackage` + `mergeJunkManifest` to register in the Manifest |
| Resource overlay | Rewrites image metadata and optional XML comments at compile time (pre-packaging source set) |
| Resource table obfuscation | Rewrites `resources.arsc` and `res` paths inside APK/AAB after packaging |
| Component rename | Maps four component types to random short FQCNs (e.g. `SplashActivity` → `e3.gj1`); patches DEX / Manifest |
| View rename | Replaces custom View FQCNs in layouts |
| Mapping merge | Merges R8, resource, and rename mappings for Crashlytics upload |
| Baseline Profile | Recompiles `baseline.prof` / `baseline.profm` using the merged mapping |
| Keep verification | Optional check that kept resources were not obfuscated |

> **Pipeline**: compile-time junk / resource overlay → R8 → post-R8 rename + resource table obfuscation → merged mapping.

## Glossary

| Term | Meaning |
|------|---------|
| AGP | Android Gradle Plugin |
| R8 | Android code shrinker / obfuscator; Release must set `isMinifyEnabled = true` |
| Post-R8 | Artifact transform stage after R8 finishes and before the final APK/AAB is produced |
| DEX | Android bytecode packaged into APK/AAB |
| Utility class | Junk Code helper class with random methods/fields; not a manifest component; Manifest unchanged by default |
| FQCN | Fully qualified class name, e.g. `com.example.SplashActivity` |
| Overlay | Compile-time rewrite layered onto source/resources before compilation |
| Mapping | Obfuscation name mapping for deobfuscation and Crashlytics |
| Manifest | `AndroidManifest.xml` |
| Variant | Build variant, e.g. `googleRelease` (flavor + buildType, lowercase) |

## Quick start

**1. Add the plugin**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("io.github.amsonix.molt") version "1.0.0" apply false
}
```

> For local plugin development, use a composite build: `pluginManagement { includeBuild("path/to/molt") }`. App-side `molt { }` config stays the same.

**2. Apply to the app module**

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("io.github.amsonix.molt")
}

android {
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(/* ... */)
        }
    }
}

molt {
    junkCode { profile.set("light") }
}
```

See [Configuration template](#configuration-template) or the [full reference](docs/CONFIG.md).

**3. Build as usual**

```bash
./gradlew :app:assembleRelease
# or
./gradlew :app:bundleRelease
```

## Common configuration

Daily use usually needs only the blocks below. **All options** (top-level, `resourceObfuscate` details, `verify*` / `failOn*`, etc.) are in [docs/CONFIG.md](docs/CONFIG.md).

### Top-level (common)

| Option | Default | Description |
|--------|---------|-------------|
| `enabled` | `true` | Master switch |
| `enabledBuildTypes` | `release` | Apply only to listed build types; add custom types (e.g. `alpha`, `debug`) as needed |
| `seed` | `applicationId.hashCode()` | Obfuscation seed; stable per applicationId |
| `autoDiscoverKeepXml` | `true` | Scan `res/raw/keep.xml` automatically |

### `junkCode { }`

Generates junk code at compile time and packages it into DEX.

| Option | Default | Description |
|--------|---------|-------------|
| `profile` | `light` | Utility class scale: `light` / `medium` / `heavy` / `custom` |
| `activityCountPerPackage` | `0` | Activities per sub-package; `0` = no components |
| `mergeJunkManifest` | `false` | Merge junk Activities into Manifest (requires previous option > 0) |

`profile` presets (utility classes only): `light` 30 classes · `medium` 100 · `heavy` 1500. See [CONFIG.md](docs/CONFIG.md#junkcode--).

```kotlin
// Default: utility classes only
molt { junkCode { profile.set("light") } }

// Utility classes + Activities in Manifest
molt {
    junkCode {
        profile.set("medium")
        activityCountPerPackage.set(2)
        mergeJunkManifest.set(true)
    }
}
```

### `componentRename { }` / `viewRename { }`

Post-R8 class renames (see [Glossary](#glossary)). Example:

```
com.example.app.SplashActivity  →  e3.gj1
```

Simple names change too — not package-only renames. Both blocks default to `enabled = true`; disable per variant if needed.

### `variantConfig { create("<variant>") { } }`

Override global settings per variant (e.g. `googleRelease`):

```kotlin
variantConfig {
    create("googleRelease") {
        junkCode { profile.set("heavy") }
        componentRename { enabled.set(false) }
    }
}
```

Override summary: [CONFIG.md → variantConfig](docs/CONFIG.md#variantconfig-createvariant--).

## Configuration template

```kotlin
molt {
    junkCode {
        profile.set("medium")
        // No Activities / Manifest by default; enable when needed:
        // activityCountPerPackage.set(2)
        // mergeJunkManifest.set(true)
    }

    resourceObfuscate {
        imageAntiDetect.set(true)
    }

    bundleResourceObfuscate {
        enabled.set(true)
        obfuscateApk.set(true)
    }

    componentRename { enabled.set(true) }
    viewRename { enabled.set(true) }

    autoDiscoverKeepXml.set(true)

    variantConfig {
        create("googleRelease") {
            junkCode { profile.set("heavy") }
            verify {
                verifyApkKeep.set(true)
                verifyBundleKeep.set(true)
            }
        }
    }
}
```

## Protecting resources

Declare resources that must stay readable in `res/raw/keep.xml`:

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@string/app_name,@layout/activity_main" />
```

- **Exact entries** (`@layout/foo`): keep original name when present in the artifact
- **Wildcard prefixes** (`@drawable/ad_*`): whitelist only; not required to exist

The plugin merges keep files from the app and library dependencies.

## Notes

1. **Release must enable R8** (`isMinifyEnabled = true`). Component / View rename patches DEX post-R8; skipped when minify is off.
2. **Default build types**: default is `enabledBuildTypes = ["release"]` only. Add `debug`, `alpha`, or other custom build types explicitly when needed.
3. **Junk** `profile` **≠ Manifest**: `light/medium/heavy` only scales utility classes; Manifest changes need `activityCountPerPackage` + `mergeJunkManifest`.
4. **Keep before obfuscate**: put ad / Firebase / SDK-critical resources in `keep.xml`; enable `verifyApkKeep` / `verifyBundleKeep` when appropriate.
5. **AGP version**: 8.13.x recommended; mismatch warns by default, or set `failOnAgpToolchainMismatch = true` to fail the build.

## Sample project

The `sample/` directory contains a minimal app + library + flavor setup:

```bash
./gradlew -p sample :app:assembleGoogleRelease
```

See [sample/README.md](sample/README.md).

## More docs

| Doc | Audience |
|-----|----------|
| [docs/CONFIG.md](docs/CONFIG.md) | Full configuration reference |
| [README.zh-CN.md](README.zh-CN.md) | Chinese documentation |
| [sample/README.md](sample/README.md) | Integration sample |
| [plugin/CHANGELOG.md](plugin/CHANGELOG.md) | Release notes |
| [plugin/README.md](plugin/README.md) | Plugin development & publishing |
