# Molt Gradle Plugin

Android **马甲包一站式混淆** Gradle 插件。

在标准 Android 构建流程上叠加编译期改写与打包产物变换，用于降低多包同构时的资源、DEX、Layout 相似度，并合成完整 mapping 供 Crashlytics 等工具使用。

## 能力概览

| 阶段 | 能力 | 说明 |
|------|------|------|
| 编译期 | **Junk Code** | 生成随机 utility 类 / Activity，可选合并进 Manifest |
| 编译期 | **资源 Overlay** | 对 `res/` 做编译期 overlay：图片 metadata 改写、XML 注入、增量 fingerprint 缓存 |
| 打包后 | **资源表混淆** | 改写 APK / AAB 内 `resources.arsc` 的 entry 名与路径（dir / file 模式） |
| 打包后 | **Component 改包** | 扫描 APK / AAB 中的 DEX，将 Activity / Service 等改写到随机包名 |
| 打包后 | **View 改类名** | 替换 layout 中自定义 View 的完整类名（binary / plain-text XML） |
| 打包后 | **图片兜底** | 对 APK / AAB 内图片 entry 做 metadata 注入与 decode 校验 |
| 打包后 | **Baseline Profile** | 按合成 mapping 重编 `baseline.prof` / `baseline.profm` |
| 产物 | **Mapping 合成** | 合并代码混淆、资源、改包映射，可选 hook Crashlytics upload |
| 产物 | **Keep 验包** | 校验 keep.xml / Firebase baseline 声明的资源未被混淆改写 |

## 环境要求

| 项 | 要求 |
|----|------|
| AGP | **8.13.x**（插件当前 pin 版本；漂移时会 warn，可设 `failOnAgpToolchainMismatch` 强制 fail） |
| JDK | **17+** |
| 代码混淆 | Release 构建须开启 `minifyEnabled true`（Component / View 改包依赖混淆后的 DEX） |
| 生效 BuildType | 默认仅 `alpha`、`release`；其他类型需在 `enabledBuildTypes` 中显式加入 |

## 接入

**Plugin ID**：`io.github.amsonix.molt`  
**扩展块**：`molt { }`

### Maven 依赖

根工程 `settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // SNAPSHOT / 私有发布需添加 Nexus 仓库
    }
}

plugins {
    id("io.github.amsonix.molt") version "1.0.0" apply false
}
```

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

### Composite build（本地联调）

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("path/to/molt")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
```

`app/build.gradle.kts` 配置同上。

## 常用配置

```kotlin
molt {
    // 仅对指定 buildType 生效（默认 alpha + release）
    enabledBuildTypes.set(listOf("release"))

    // 混淆种子；默认由 applicationId 推导
    // seed.set(42)

    junkCode {
        profile.set("light")              // light / medium / heavy / custom
        mergeJunkManifest.set(false)
    }

    resourceObfuscate {
        imageAntiDetect.set(true)         // 编译期图片 metadata 改写
        renameXmlFiles.set(false)
    }

    bundleResourceObfuscate {
        enabled.set(true)                 // AAB resources.arsc 混淆
        obfuscateApk.set(true)            // APK resources.arsc 混淆
    }

    componentRename { enabled.set(true) }   // Component 改包
    viewRename { enabled.set(true) }      // View 改类名

    autoDiscoverKeepXml.set(true)         // 自动扫描 keep.xml

    variantConfig {
        create("googleRelease") {
            junkCode { profile.set("medium") }
        }
    }
}
```

执行 `assemble*` / `bundle*` 时会自动串联资源 overlay → 产物变换 → mapping 合成，无需手动触发任务。

## 构建产物

合成 mapping 默认输出至：

```
app/build/outputs/mapping/<variant>/shell-obfuscate-mapping.txt
```

集成 Firebase Crashlytics 时，插件默认 hook `uploadCrashlyticsMappingFile*` 任务读取上述文件（可通过 `hookCrashlyticsMappingUpload` 关闭）。

## Keep 资源

在各模块 `res/raw/keep.xml` 中声明不可混淆的资源：

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@string/app_name,@layout/activity_main" />
```

- **精确条目**（如 `@layout/foo`）：资源存在于制品中时保留原名
- **通配前缀**（如 `@drawable/ad_*`）：仅参与白名单，不要求 APK/AAB 必含

插件会自动发现并合并 app 与各 `android.library` 模块中的 keep 文件。Firebase 集成工程可额外开启 `useFirebaseArtifactVerifyBaseline` 做构建期验包。

## 示例工程

`sample` 提供 app + library + flavor 的最小接入示例（部分能力默认关闭以便快速构建）：

```bash
./gradlew -p sample :app:assembleGoogleRelease
```

详见 [sample/README.md](sample/README.md)。

## 更多文档

| 文档 | 说明 |
|------|------|
| [sample/README.md](sample/README.md) | 示例工程说明 |
| [plugin/CHANGELOG.md](plugin/CHANGELOG.md) | 版本变更记录 |
| [plugin/README.md](plugin/README.md) | 插件开发、发布与 CI 验证 |
