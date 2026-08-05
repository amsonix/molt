# Molt Gradle Plugin

Android **马甲包一站式混淆** Gradle 插件。

在标准 Android 构建流程上叠加编译期改写与 post-R8 产物变换，用于降低多包同构时的资源、DEX、Layout 相似度，并合成完整 mapping 供 Crashlytics 等工具使用。

## 能力概览

按构建阶段划分如下：

| 阶段 | 能力 | 说明 |
|------|------|------|
| 编译期 | **Junk Code** | 生成随机 utility 类 / Activity，可选合并进 Manifest |
| 编译期 | **资源 Overlay** | 对 `res/` 做编译期 overlay：图片 metadata 改写、XML 注入、增量 fingerprint 缓存 |
| post-R8 | **资源表混淆** | 改写 APK / AAB 内 `resources.arsc` 的 entry 名与路径（dir / file 模式） |
| post-R8 | **Component 改包** | 扫描 R8 后 DEX，将 Activity / Service 等改写到随机包名 |
| post-R8 | **View 改类名** | 替换 layout 中自定义 View 的 FQCN（binary / plain-text XML） |
| post-R8 | **图片兜底** | Transform 阶段对 APK / AAB 内图片 entry 做 metadata 注入与 decode 校验 |
| post-R8 | **Baseline Profile** | 按合成 mapping 重编 `baseline.prof` / `baseline.profm` |
| 产物 | **Mapping 合成** | 合并 R8、资源、rename 映射，可选 hook Crashlytics upload |
| 产物 | **Keep 验包** | 校验 keep.xml / Firebase baseline 声明的资源未被混淆改写 |

### 子模块

| 模块 | 职责 |
|------|------|
| `resource-keep` | `keep.xml` 解析、合并与 Firebase / SDK 静态 baseline |
| `plugin` | Gradle 插件实现与 variant 任务接线 |
| `plugin/sample` | 最小示例（app + library + flavor） |

### 典型产物变换链路

```
R8 产出 APK/AAB
    → 资源表混淆 (resources.arsc)
    → DEX Component 改包 + Layout View 改类名
    → 图片 metadata 兜底
    → Baseline Profile 重编
    → 重签名 / zipalign
    → Keep / 图片验包（可选）
    → 合成 mapping.txt
```

## 快速开始

**Plugin ID**：`io.github.amsonix.molt`  
**扩展块**：`molt { }`

### Composite build（开发联调）

宿主 `settings.gradle.kts`：

```kotlin
pluginManagement {
    includeBuild("path/to/molt")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
```

`app/build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application")
    id("io.github.amsonix.molt")
}

molt {
    junkCode { profile.set("light") }
}
```

### 构建示例工程

```bash
./gradlew -p plugin/sample :app:assembleGoogleRelease
```

### 插件自测

```bash
./gradlew :plugin:moltObfuscateCheck              # 单元测试
./gradlew :plugin:moltObfuscateTransformE2eTest   # TestKit E2E（需 Android SDK）
./tools/molt-verify.sh                            # nightly 套件
```

## 发布 Maven

版本号：`gradle.properties` → `moltVersion`

```bash
./gradlew :plugin:publishMoltObfuscatePlugin
```

| 坐标 | 说明 |
|------|------|
| `io.github.amsonix.molt:resource-keep:<version>` | keep 库 |
| `io.github.amsonix.molt:io.github.amsonix.molt.gradle.plugin:<version>` | 插件 marker |
| `io.github.amsonix.molt:plugin:<version>` | 插件实现 |

需配置 `NEXUS_USERNAME` / `NEXUS_PASSWORD`（或写入 `gradle.properties`）。

## 可选：宿主工程集成探针

若本地有完整 Android 宿主工程（含 `app` 模块），可指定根目录：

```bash
export MOLT_INTEGRATION_ROOT=/path/to/host-android-project
RUN_MAPPING_PARITY=1 ./tools/molt-verify.sh
```

或通过 Gradle：`-PintegrationRoot=/path/to/host-android-project`

## Gradle 任务

以 app 模块 `googleRelease` variant 为例：

| 任务 | 作用 |
|------|------|
| `moltObfuscatePrepareMappingGoogleRelease` | 准备 mapping 输入 |
| `moltObfuscateResourcesGoogleRelease` | 编译期资源 overlay |
| `moltObfuscateJunkCodeGoogleRelease` | 生成 junk 源码 |
| `moltObfuscateTransformApkGoogleRelease` | post-R8 APK 变换 |
| `moltObfuscateTransformBundleGoogleRelease` | post-R8 AAB 变换 |
| `moltObfuscateMergeMappingGoogleRelease` | 合成最终 mapping |
| `moltObfuscateGenerateJunkKeep` | 生成 junk 对应 ProGuard keep |

插件模块验证任务：`:plugin:moltObfuscateCheck`、`:plugin:moltObfuscateNightlyVerify` 等。

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
