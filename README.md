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

## 配置说明

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
        enabled.set(true)    // AAB 资源表混淆
        obfuscateApk.set(true) // APK 资源表混淆
    }

    componentRename { enabled.set(true) }
    viewRename { enabled.set(true) }

    autoDiscoverKeepXml.set(true)

    // 按 flavor + buildType 单独调整
    variantConfig {
        create("googleRelease") {
            junkCode { profile.set("heavy") }
        }
    }
}
```

### 关键选项

| 选项 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 总开关 |
| `enabledBuildTypes` | `alpha`, `release` | 仅列出的 buildType 生效 |
| `seed` | 由 `applicationId` 推导 | 混淆随机种子，同包名保持一致 |
| `junkCode.profile` | `light` | Junk 量级：`light` / `medium` / `heavy` / `custom` |
| `resourceObfuscate.imageAntiDetect` | `true` | 编译期图片 metadata 改写 |
| `componentRename.enabled` | `true` | Component 改包 |
| `viewRename.enabled` | `true` | View 改类名 |
| `autoDiscoverKeepXml` | `true` | 自动扫描各模块 `res/raw/keep.xml` |

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
