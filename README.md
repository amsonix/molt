# Molt Gradle Plugin

为 Android **多包 / 马甲包**场景提供构建期混淆能力：在常规 `assemble` / `bundle` 流程中自动注入 Junk Code、改写资源与 DEX，并输出完整 mapping。

| | |
|---|---|
| Plugin ID | `io.github.amsonix.molt` |
| 扩展块 | `molt { }` |
| 当前版本 | `1.0.0` |
| 要求 | AGP 8.13.x · JDK 17+ · Release 开启代码混淆 |

## 快速开始

**1. 引入插件**

`settings.gradle.kts`：

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

**2. 应用到 app 模块**

`app/build.gradle.kts`：

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

**3. 正常构建**

```bash
./gradlew :app:assembleRelease
# 或
./gradlew :app:bundleRelease
```

插件会自动完成资源 overlay、APK/AAB 产物变换与 mapping 合成，**无需额外 Gradle 任务**。

> 本地开发插件本身时，可用 Composite build：`pluginManagement { includeBuild("path/to/molt") }`，app 侧配置不变。

## 它能做什么

### 增加包体差异

| 能力 | 说明 |
|------|------|
| Junk Code | 生成随机 Java 类，可选写入 Manifest（`light` / `medium` / `heavy`） |
| 资源 Overlay | 编译期改写图片 metadata、注入 XML 注释，支持增量缓存 |
| 资源表混淆 | 改写 APK/AAB 内 `resources.arsc` 的资源名与路径 |
| Component 改包 | 将 Activity、Service 等组件移到随机包名 |
| View 改类名 | 替换 layout 中自定义 View 的类名 |

### 构建集成

| 能力 | 说明 |
|------|------|
| Mapping 合成 | 合并代码混淆、资源、改包映射，输出到 `app/build/outputs/mapping/<variant>/shell-obfuscate-mapping.txt` |
| Crashlytics | 默认 hook `uploadCrashlyticsMappingFile*`，上传合成 mapping（`hookCrashlyticsMappingUpload` 可关） |
| Baseline Profile | 按合成 mapping 重编 `baseline.prof` / `baseline.profm` |
| Keep 验包 | 可选校验 keep 资源未被误混淆（`verifyApkKeep` / `verifyBundleKeep`） |

## 配置参考

以下为 `molt { }` 扩展块全部公开配置项。未列出的 `variantConfig` 子项见各表「variant 可覆盖」列。

### 顶层

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | `Boolean` | `true` | 插件总开关 |
| `enabledBuildTypes` | `List<String>` | `alpha`, `release` | 仅对列出的 buildType 生效 |
| `seed` | `Int` | `applicationId.hashCode()` | 混淆随机种子；同包名保持一致 |
| `keepXmlFiles` | `FileCollection` | 空 | 额外 keep.xml 文件，与自动发现合并 |
| `autoDiscoverKeepXml` | `Boolean` | `true` | 自动扫描 app 与 library 的 `res/raw/keep.xml` |
| `mergeShrinkKeepXml` | `Boolean` | `false` | 合并外部 shrink-resources 插件产出的 keep |
| `shrinkKeepRelativePath` | `String` | `generated/shrink-resources/{variant}/res/raw/keep.xml` | shrink keep 路径模板 |
| `shrinkKeepGenerateTaskName` | `String` | `generateShrinkKeepXml{Variant}` | shrink keep 生成任务名模板 |
| `verifyApkKeep` | `Boolean` | `false` | APK 构建后校验 keep 资源未被混淆 |
| `failOnMissingApkKeep` | `Boolean` | `true` | `verifyApkKeep` 发现缺失时 fail build |
| `verifyBundleKeep` | `Boolean` | `false` | AAB 构建后校验 keep 资源未被混淆 |
| `failOnMissingBundleKeep` | `Boolean` | `true` | `verifyBundleKeep` 发现缺失时 fail build |
| `useFirebaseArtifactVerifyBaseline` | `Boolean` | `false` | 启用 Firebase/google-services 内置验包 baseline |
| `hookCrashlyticsMappingUpload` | `Boolean` | `true` | hook Crashlytics 上传任务读取合成 mapping |
| `failOnEmptyArtifactVerifyBaseline` | `Boolean` | `true` | 验包开启但 baseline 为空时 fail build |
| `failOnMissingShrinkKeepTask` | `Boolean` | `true` | `mergeShrinkKeepXml` 开启但任务不存在时 fail |
| `failOnJunkManifestMergeFailure` | `Boolean` | `true` | Junk Manifest 合并失败时 fail build |
| `allowUnsignedOutput` | `Boolean` | `false` | 允许输出未签名包（仅本地调试） |
| `failOnAgpToolchainMismatch` | `Boolean` | `false` | AGP 与插件 pin 版本不一致时 fail build |
| `axmlStrictMode` | `Boolean` | `false` | binary layout 无法改 View 类名时 fail build |
| `projectPackagePrefixes` | `List<String>` | 由 `applicationId` 推导 | DEX 伴生类识别的工程包前缀 |
| `syncBaselineProfile` | `Boolean` | `true` | 按合成 mapping 重编 baseline profile |
| `failOnBaselineProfileSyncFailure` | `Boolean` | `true` | profile 重编失败时 fail build |
| `baselineProfileHumanReadable` | `File` | variant 默认路径 | 覆盖 `baseline-prof.txt` 输入 |

### `junkCode { }`

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | Junk Code 开关 | ✓ |
| `profile` | `String` | `light` | 量级 preset，见下表 | ✓ |
| `packageCount` | `Int` | `5` | 子包数量（`custom` 时生效） | ✓ |
| `classCount` | `Int` | `30` | utility 类总数（`custom` 时生效） | ✓ |
| `methodsPerClass` | `Int` | `8` | 每类方法数（`custom` 时生效） | ✓ |
| `activityCountPerPackage` | `Int` | `0` | 每子包 Activity 数 | ✓ |
| `excludeActivityJavaFile` | `Boolean` | `false` | 跳过 Activity `.java`，仍生成 layout / Manifest | ✓ |
| `mergeJunkManifest` | `Boolean` | `false` | 将 Junk Activity 写入 Manifest | ✓ |
| `resPrefix` | `String` | `junk_` | Activity layout 资源名前缀 | ✓ |
| `packagePrefix` | `String` | `{applicationId}.shell.junk` | Junk 类包名前缀 | — |

**`profile` preset 对照**

| profile | 子包数 | 类数 | 每类方法数 |
|---------|--------|------|-----------|
| `light` | 5 | 30 | 8 |
| `medium` | 10 | 100 | 12 |
| `heavy` | 30 | 1500 | 20 |
| `custom` | 使用上方 `packageCount` / `classCount` / `methodsPerClass` | | |

### `resourceObfuscate { }`

编译期资源 overlay（图片改写、XML 注入等）。

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | 资源 overlay 开关 | ✓ |
| `renameXmlFiles` | `Boolean` | `false` | 混淆 XML 文件名 | ✓ |
| `injectXmlJunk` | `Boolean` | `false` | 在 layout 末尾注入注释占位 | ✓ |
| `imageAntiDetect` | `Boolean` | `true` | 编译期图片 metadata 改写 | ✓ |
| `imageMicroCompress` | `Boolean` | `true` | 图片微压缩总开关 | — |
| `imagePngMicroCompress` | `Boolean` | `false` | PNG 微压缩 | ✓ |
| `imageJpegMicroCompress` | `Boolean` | `true` | JPEG 微压缩 | ✓ |
| `imageMicroCompressQuality` | `Float` | `0.97` | 微压缩质量（0~1） | — |
| `imageJpegMetadataMode` | `String` | `both` | JPEG metadata 注入模式：`com` / `exif` / `both` | — |
| `imagePngExtraChunks` | `Boolean` | `true` | PNG 追加 extra chunk | — |
| `imagePerceptualNoise` | `Boolean` | `false` | LSB 微扰动（防 pHash 场景） | — |
| `verifyImageAntiDetect` | `Boolean` | `true` | overlay 阶段校验图片改写生效 | — |
| `failOnUnchangedImageAntiDetect` | `Boolean` | `true` | 图片未改写时 fail build | — |
| `imageAntiDetectApkFallback` | `Boolean` | `true` | APK 产物变换阶段图片 metadata 兜底 | — |
| `verifyApkImageAntiDetect` | `Boolean` | `false` | APK 构建后 decode 校验全部 res 图片 | — |
| `failOnApkImageAntiDetectFailure` | `Boolean` | `true` | APK 图片校验失败时 fail build | — |
| `failOnSkippedUnsupportedImageAntiDetect` | `Boolean` | `false` | overlay 无法处理 PNG/JPEG 时 fail build | — |
| `imageAntiDetectBundleFallback` | `Boolean` | `true` | AAB 产物变换阶段图片 metadata 兜底 | — |
| `verifyBundleImageAntiDetect` | `Boolean` | `false` | AAB 构建后 decode 校验全部 res 图片 | — |
| `failOnBundleImageAntiDetectFailure` | `Boolean` | `true` | AAB 图片校验失败时 fail build | — |
| `overlayParallelism` | `Int` | `0` | overlay 并行度；`0` = min(4, CPU) | — |
| `incrementalOverlay` | `Boolean` | `true` | 按 res 目录 fingerprint 增量 skip | ✓ |
| `maxWebpExtendedSkipRatio` | `Double` | `0.05` | WebP 扩展格式 skip 占比阈值；`0` = 不校验 | — |

### `bundleResourceObfuscate { }`

APK / AAB 内 `resources.arsc` 与 res 路径混淆。

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | AAB 资源表混淆 | ✓ |
| `obfuscateApk` | `Boolean` | `true` | APK 资源表混淆 | ✓ |
| `obfuscationMode` | `String` | `default` | 混淆模式：`default` / `dir` / `file` | — |
| `mappingFile` | `File` | 自动生成 | 增量复用的 `resources-mapping.txt` | — |
| `reuseIncrementalMapping` | `Boolean` | `true` | 自动复用上次 Transform 的 mapping | — |

### `componentRename { }`

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | Component（Activity / Service 等）改包 | ✓ |
| `excludePatterns` | `List<String>` | `*.debug.*`, `*Hilt_*`, `*_HiltModules*` | 不参与改包的类名 glob | — |

### `viewRename { }`

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | 自定义 View 改类名 | ✓ |
| `excludePatterns` | `List<String>` | `*.debug.*`, `*Hilt_*`, `*_HiltModules*` | 不参与改名的类名 glob | — |
| `excludeResXmlEntryPatterns` | `List<String>` | 内置广告 SDK layout 规则 | 跳过改写的 layout 路径 glob | — |

### `variantConfig { create("<variant>") { } }`

按 variant 名（如 `googleRelease`）覆盖全局配置。variant 名 = flavor + buildType 拼接（全小写）。

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

| 子块 | 可覆盖项 |
|------|----------|
| （顶层） | `seed` |
| `junkCode` | `enabled`, `profile`, `packageCount`, `classCount`, `methodsPerClass`, `activityCountPerPackage`, `excludeActivityJavaFile`, `mergeJunkManifest`, `resPrefix` |
| `resourceObfuscate` | `enabled`, `renameXmlFiles`, `injectXmlJunk`, `imageAntiDetect`, `imagePngMicroCompress`, `imageJpegMicroCompress`, `incrementalOverlay` |
| `bundleResourceObfuscate` | `enabled`, `obfuscateApk` |
| `componentRename` | `enabled` |
| `viewRename` | `enabled` |
| `verify` | `verifyApkKeep`, `verifyBundleKeep` |

## 配置说明

查阅上方配置参考后，可按场景选用以下模板。

### 最小配置

只开 Junk Code，其余走默认值：

```kotlin
molt {
    junkCode { profile.set("light") }
}
```

### 生产环境推荐

```kotlin
molt {
    enabledBuildTypes.set(listOf("release"))

    junkCode {
        profile.set("medium")
        mergeJunkManifest.set(false)
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
        }
    }
}
```

## 保护关键资源

在任意模块的 `res/raw/keep.xml` 中声明不可混淆的资源：

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@string/app_name,@layout/activity_main" />
```

- **精确条目**（`@layout/foo`）：制品中存在时保留原名
- **通配前缀**（`@drawable/ad_*`）：仅作白名单，不要求制品必含

插件会自动合并 app 与依赖 library 中的 keep 文件。

## 注意事项

1. **Release 必须开启代码混淆**（`isMinifyEnabled = true`），Component / View 改包才有可处理的 DEX。
2. **Debug 默认不生效**；若需对 debug 构建启用，加入 `enabledBuildTypes`。
3. **keep 先于混淆**：SDK 关键资源（广告、Firebase 等）务必写入 keep.xml，避免运行时找不到资源。
4. **AGP 版本**：建议使用 8.13.x；版本不一致时会 warn，可通过 `failOnAgpToolchainMismatch` 改为 fail build。

## 示例工程

仓库 `sample/` 目录提供 app + library + flavor 的最小示例：

```bash
./gradlew -p sample :app:assembleGoogleRelease
```

详见 [sample/README.md](sample/README.md)。

## 更多文档

| 文档 | 受众 |
|------|------|
| [sample/README.md](sample/README.md) | 接入示例 |
| [plugin/CHANGELOG.md](plugin/CHANGELOG.md) | 版本变更 |
| [plugin/README.md](plugin/README.md) | 插件开发与发布 |
