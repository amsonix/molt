# Molt Gradle Plugin

Android **马甲包一站式混淆** Gradle 插件。

能力：Junk Code、编译期资源 overlay、APK/AAB `resources.arsc` 混淆、post-R8 Component/View 改包、Mapping 合成、keep 验包。

## 模块

| 模块 | 说明 |
|------|------|
| `resource-keep` | keep.xml 解析与 baseline |
| `plugin` | Gradle 插件实现 |
| `plugin/sample` | 最小示例（app + library + flavor） |

## 快速开始

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
./tools/molt-verify.sh                   # nightly 套件
```

## 发布 Maven

版本：`gradle.properties` → `moltVersion`

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

## DSL

- **Plugin ID**：`io.github.amsonix.molt`
- **代码包名**：`io.github.amsonix.molt`（实现类在 `io.github.amsonix.molt.internal.*`）
- **扩展块**：`molt { }`

## Gradle 任务（app 模块，以 `googleRelease` 为例）

| 旧名 (shell-obfuscate) | 新名 (moltObfuscate) |
|------------------------|----------------------|
| `shellObfuscatePrepareMappingGoogleRelease` | `moltObfuscatePrepareMappingGoogleRelease` |
| `shellObfuscateResourcesGoogleRelease` | `moltObfuscateResourcesGoogleRelease` |
| `shellObfuscateJunkCodeGoogleRelease` | `moltObfuscateJunkCodeGoogleRelease` |
| `shellObfuscateTransformApkGoogleRelease` | `moltObfuscateTransformApkGoogleRelease` |
| `shellObfuscateTransformBundleGoogleRelease` | `moltObfuscateTransformBundleGoogleRelease` |
| `shellObfuscateMergeMappingGoogleRelease` | `moltObfuscateMergeMappingGoogleRelease` |
| `shellObfuscateGenerateJunkKeep` | `moltObfuscateGenerateJunkKeep` |

插件模块验证任务：`:plugin:moltObfuscateCheck`、`:plugin:moltObfuscateNightlyVerify` 等。
