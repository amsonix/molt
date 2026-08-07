# AGP compatibility

Chinese: [COMPATIBILITY.zh-CN.md](COMPATIBILITY.zh-CN.md)

Supported **AGP range is probed**, not assumed. Run `./tools/agp-compat.sh` and read `build/reports/agp-compat/report.md`.

## Verified range

**Full pipeline (5 probes): AGP 8.0.0 – 9.3.0** — matrix rows 8.0.2 – 9.3.0 = 18 × 5 = **90/90 PASS** (2026-08-06, local); AGP **8.0.0** verified separately **5/5 PASS** (2026-08-07, local).

## Probe matrix (18 versions)

[`tools/agp-compat-matrix.txt`](../tools/agp-compat-matrix.txt):

| Probe | Gradle task | Validates |
|-------|-------------|-----------|
| Smoke | `moltObfuscateAgpCompatTest` | prepare / resources / junk / mapping wiring + **Crashlytics upload 接线** |
| APK E2E | `moltObfuscateAgpCompatE2eTest` | assemble + APK transform |
| AAB E2E | `moltObfuscateAgpCompatBundleE2eTest` | bundle + AAB transform |
| APK rename | `moltObfuscateAgpCompatRenameApkE2eTest` | assemble + transform + merge mapping + **Crashlytics 合成 mapping** |
| AAB rename | `moltObfuscateAgpCompatRenameAabE2eTest` | bundle + transform + merge mapping + **Crashlytics 合成 mapping** |

```bash
./tools/agp-compat.sh
```

**Requirements:** JDK 17–21 (Gradle 8.0–8.4 TestKit cannot run on JDK 22+). AGP 9.x rows use Gradle 9.x. The script auto-selects Temurin 17.

### Crashlytics upload 探针

| AGP | Crashlytics 插件 | smoke 断言 | rename E2E 断言 |
|-----|------------------|------------|-----------------|
| 8.0.2 – 8.7.x | 2.9.9 | upload **dependsOn** merge + `mappingFileProvider` → 合成 mapping | merge 后 mapping 文件 + upload 接线 |
| 8.8 – 9.3.0 | 3.0.3 | 同上 + `mergedMappingFile` 路径 | 同上 + `mergedMappingFile` 指向实际文件 |

不执行真实 Firebase upload（无网络/凭证）。

## Gradle pairing (matrix)

| AGP | Gradle |
|-----|--------|
| 8.0.2 – 8.13.2 | see [matrix](../tools/agp-compat-matrix.txt) |
| 9.0.0 | 9.1 |
| 9.1.1 | 9.3.1 |
| 9.2.0 | 9.4.1 |
| 9.3.0 | 9.5 |

## aapt2 / APK paths

| AGP | aapt2 | APK metadata dir |
|-----|-------|------------------|
| 8.10.1+ | `SdkComponents.aapt2` | — |
| 8.0 – 8.9 | SDK `build-tools/*/aapt2` | — |
| 8.7+ | — | `outputs/apk/{flavor}/{buildType}/` |
| 8.0 – 8.6 | — | `outputs/apk/{variantName}/` |

## Limits

- **&lt; AGP 8.0** — no Variant Artifact Transform API
- **AGP 8.x** requires **Gradle 8.x**; **AGP 9.x** requires **Gradle 9.x**
- Plugin pins `aapt2-proto` / `bundletool` to **8.13.2** (warn on drift)
- Old AGP R8 mapping may live under `outputs/mapping/{variant}/` (handled by `ObfuscationMappingFileResolver`)

## CI

[`.github/workflows/agp-compat.yml`](../.github/workflows/agp-compat.yml) — 18 parallel rows; **8.13.2** gates merge.

## Feature probe（功能维度）

AGP 矩阵 **不** 覆盖全部 `molt {}` 配置组合。功能维度见 [FEATURE_PROBE.md](FEATURE_PROBE.md) 与 [`tools/feature-probe-matrix.txt`](../tools/feature-probe-matrix.txt)（`./tools/feature-probe.sh`）。

## Upgrading AGP (checklist)

When upgrading AGP or Gradle in a host project:

| Step | Command / action |
|------|------------------|
| 1. Matrix smoke | `./tools/agp-compat.sh` or CI `agp-compat.yml` |
| 2. Feature gate | `FEATURE_PROBE_TIER=gate ./tools/feature-probe.sh` |
| 3. Pin drift | `molt { failOnAgpToolchainMismatch.set(true) }` during upgrade PR |
| 4. Nightly (optional) | `./tools/molt-verify.sh` or `moltObfuscateNightlyVerify` |
| 5. Host verify | `./gradlew :app:moltPrintVariantPlan` + check `shell-obfuscate-mapping.txt` |

Recommended production flags after upgrade:

```kotlin
molt {
    failOnAgpToolchainMismatch.set(true)
    failOnCrashlyticsHookFailure.set(true) // when Crashlytics is applied
}
```

## Configuration Cache

Molt tasks use Gradle `@Input` / `@Output` and `Provider` wiring compatible with **Configuration Cache**.

Enable in host `gradle.properties`:

```properties
org.gradle.configuration-cache=true
```

If a build fails with configuration-cache errors after enabling, run once with `--no-configuration-cache` and report the stack trace. AGP matrix probes do not explicitly gate CC yet; host projects on Gradle 8.5+ are encouraged to validate locally.
