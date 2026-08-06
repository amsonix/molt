# Molt Gradle Plugin（实现模块）

插件实现源码。面向使用者的接入说明见仓库根目录 [README.md](../README.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)。

## 开发命令

```bash
# 单元测试（PR gate）
./gradlew :plugin:moltObfuscateCheck

# Feature probe gate（5 行）
FEATURE_PROBE_TIER=gate ./tools/feature-probe.sh

# AGP 兼容矩阵（18 版本 × 5 探针 = 90/90，本地约 1–2h）
./tools/agp-compat.sh
# 报告 → build/reports/agp-compat/report.md

# Feature probe 全矩阵
./tools/feature-probe.sh

# nightly 验证套件
./tools/molt-verify.sh

# 构建 sample 工程
./gradlew :plugin:moltObfuscateSampleAssemble
```

详见 [docs/COMPATIBILITY.md](../docs/COMPATIBILITY.md)、[docs/FEATURE_PROBE.md](../docs/FEATURE_PROBE.md)。

## 发布

版本号：`gradle.properties` → `moltVersion`（当前 **1.1.0**）

### Gradle Plugin Portal

```bash
./gradlew :plugin:publishMoltToGradlePortal \
  -Pgradle.publish.key=... \
  -Pgradle.publish.secret=...
```

需配置 Portal 凭证（建议写入 `~/.gradle/gradle.properties`，勿提交仓库）。

### 内部 Nexus

```bash
./gradlew :plugin:publishMoltObfuscatePlugin
```

需配置 `NEXUS_USERNAME` / `NEXUS_PASSWORD`（或写入 `gradle.properties`）。

| 坐标 | 说明 |
|------|------|
| `io.github.amsonix.molt:resource-keep:<version>` | keep 库 |
| `io.github.amsonix.molt:io.github.amsonix.molt.gradle.plugin:<version>` | 插件 marker |
| `io.github.amsonix.molt:plugin:<version>` | 插件实现 |

## 宿主工程集成探针

本地有完整 Android 宿主工程（含 `app` 模块）时，可指定根目录跑 mapping parity 等 nightly 检查：

```bash
export MOLT_INTEGRATION_ROOT=/path/to/host-android-project
RUN_MAPPING_PARITY=1 ./tools/molt-verify.sh
```

或通过 Gradle：`-PintegrationRoot=/path/to/host-android-project`

## Gradle 任务

### App 模块（接入方）

以 `googleRelease` variant 为例，任务会自动挂接到 `assemble` / `bundle` 流程：

| 任务 | 作用 |
|------|------|
| `moltPrintVariantPlan` | 诊断：打印各 variant 开关与任务/产物路径 |
| `moltObfuscatePrepareMappingGoogleRelease` | 准备 component/view 改类名 mapping |
| `moltObfuscateResourcesGoogleRelease` | 编译期资源 overlay |
| `moltObfuscateJunkCodeGoogleRelease` | 生成 junk 源码 |
| `moltObfuscateTransformApkGoogleRelease` | APK 产物变换 |
| `moltObfuscateTransformBundleGoogleRelease` | AAB 产物变换 |
| `moltObfuscateMergeMappingGoogleRelease` | 合成最终 mapping |
| `moltObfuscateGenerateJunkKeep` | 生成 junk 对应 ProGuard keep |

### 插件模块验证

| 任务 | 作用 |
|------|------|
| `moltObfuscateCheck` | 单元测试（PR gate） |
| `moltObfuscateAgpCompatTest` | 单档 AGP smoke（`-PtestAgp` / `-PtestGradle`） |
| `moltObfuscateAgpCompatMatrix` | 全 AGP 矩阵脚本 |
| `moltObfuscateFeatureProbeTest` | 单档 feature probe（`-PmoltFeature=`） |
| `moltObfuscateFeatureProbeMatrix` | 全 feature 矩阵脚本 |
| `moltObfuscateNightlyVerify` | nightly 聚合 |

<details>
<summary>从 shell-obfuscate 迁移的任务名对照</summary>

| 旧名 | 新名 |
|------|------|
| `shellObfuscatePrepareMapping*` | `moltObfuscatePrepareMapping*` |
| `shellObfuscateResources*` | `moltObfuscateResources*` |
| `shellObfuscateJunkCode*` | `moltObfuscateJunkCode*` |
| `shellObfuscateTransformApk*` | `moltObfuscateTransformApk*` |
| `shellObfuscateTransformBundle*` | `moltObfuscateTransformBundle*` |
| `shellObfuscateMergeMapping*` | `moltObfuscateMergeMapping*` |
| `shellObfuscateGenerateJunkKeep` | `moltObfuscateGenerateJunkKeep` |

</details>
