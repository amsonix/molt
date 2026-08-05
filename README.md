# Molt Gradle Plugin

为 Android **多包 / 马甲包**场景提供构建期混淆：在常规 `assemble` / `bundle` 流程中自动注入 Junk Code、改写资源与 DEX，并输出完整 mapping。**无需额外 Gradle 任务**。


| 项 | 值 |
|----|-----|
| Plugin ID | `io.github.amsonix.molt` |
| 扩展块 | `molt { }` |
| 当前版本 | `1.0.0` |
| 要求 | AGP 8.13.x · JDK 17+ · Release 开启 `isMinifyEnabled` |


## 它能做什么


| 能力               | 说明                                                                                                                                         |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Junk Code        | 生成 [utility 类](#术语说明) 增加 DEX 差异（`light` / `medium` / `heavy`）；可选生成 Activity，需 `activityCountPerPackage` + `mergeJunkManifest` 才写入 Manifest |
| 资源 Overlay       | 编译期 source set 改写图片 metadata、可选 XML 注释（打包前）                                                                                                |
| 资源表混淆            | 产物打包后改写 APK/AAB 内 `resources.arsc` 与 res 路径                                                                                                |
| Component 改类名    | 将四大组件完整类名映射为随机短名（如 `SplashActivity` → `e3.gj1`），改写 DEX / Manifest                                                                          |
| View 改类名         | 替换 layout 中自定义 View 的完整类名                                                                                                                  |
| Mapping 合成       | 合并 R8、资源、改类名对照表，供 Crashlytics 上传                                                                                                           |
| Baseline Profile | 按合成 mapping 重编 `baseline.prof` / `baseline.profm`                                                                                          |
| Keep 验包          | 可选校验 keep 资源未被误混淆                                                                                                                          |


> **执行顺序**：编译期 Junk / 资源 Overlay → R8 代码混淆 → R8 完成后改类名与资源表混淆 → 合成 mapping。



## 术语说明


| 术语         | 含义                                                                |
| ---------- | ----------------------------------------------------------------- |
| AGP        | Android Gradle Plugin，Android 构建插件                                |
| R8         | Android 官方代码压缩与混淆工具；Release 需开启 `isMinifyEnabled`                 |
| R8 完成后     | R8 跑完、APK/AAB 最终打包前的产物变换阶段（文档中曾写作 post-R8）                        |
| DEX        | Android 字节码，打进 APK/AAB 的可执行代码                                     |
| utility 类  | Junk Code 生成的普通 Java 工具类，仅含随机方法/字段；不声明 Activity 等组件，默认不写 Manifest |
| 完整类名（FQCN） | 含包名的类全名，如 `com.example.SplashActivity`                            |
| Overlay    | 编译期在源码 / 资源目录上叠加改写，再参与编译（打包前）                                     |
| mapping    | 混淆前后名称对照表，用于崩溃还原与 Crashlytics                                     |
| Manifest   | 应用清单 `AndroidManifest.xml`                                        |
| variant    | 构建变体，如 `googleRelease`（flavor + buildType，全小写）                    |




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

> 本地开发插件本身时，可用 Composite build：`pluginManagement { includeBuild("path/to/molt") }`，app 侧 `molt { }` 配置不变。

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

更多配置见 [配置模板](#配置模板) 或 [完整配置参考](docs/CONFIG.md)。

**3. 正常构建**

```bash
./gradlew :app:assembleRelease
# 或
./gradlew :app:bundleRelease
```



## 常用配置

日常接入通常只需下列子块。**全部选项**（顶层、`resourceObfuscate` 细项、`verify*` / `failOn*` 等）见 [docs/CONFIG.md](docs/CONFIG.md)。

### 顶层（常用）


| 选项                    | 默认值                        | 说明                      |
| --------------------- | -------------------------- | ----------------------- |
| `enabled`             | `true`                     | 插件总开关                   |
| `enabledBuildTypes`   | `alpha`, `release`         | 仅对列出的 buildType 生效      |
| `seed`                | `applicationId.hashCode()` | 混淆随机种子；同包名保持一致          |
| `autoDiscoverKeepXml` | `true`                     | 自动扫描 `res/raw/keep.xml` |




### `junkCode { }`

编译期生成 Junk 代码并打进 DEX。


| 选项                        | 默认值     | 说明                                                  |
| ------------------------- | ------- | --------------------------------------------------- |
| `profile`                 | `light` | utility 类量级：`light` / `medium` / `heavy` / `custom` |
| `activityCountPerPackage` | `0`     | 每子包 Activity 数；`0` = 不生成组件                          |
| `mergeJunkManifest`       | `false` | 合并 Junk Activity 到 Manifest（需上项 > 0）                |


`profile` preset（仅 utility 类）：`light` 30 类 · `medium` 100 类 · `heavy` 1500 类。详见 [CONFIG.md](docs/CONFIG.md#junkcode--)。

```kotlin
// 默认：仅 utility 类
molt { junkCode { profile.set("light") } }

// utility + Activity 并写入 Manifest
molt {
    junkCode {
        profile.set("medium")
        activityCountPerPackage.set(2)
        mergeJunkManifest.set(true)
    }
}
```



### `componentRename { }` / `viewRename { }`

R8 完成后改类名（见 [术语说明](#术语说明)）。组件表示例：

```
com.shortvideo.playlet.SplashActivity  →  e3.gj1
```

类名 simple name **也会变**，不是「只换包名」。两子块默认均为 `enabled = true`，可按 variant 关闭。

### `variantConfig { create("<variant>") { } }`

按 variant 名（如 `googleRelease`）覆盖全局配置：

```kotlin
variantConfig {
    create("googleRelease") {
        junkCode { profile.set("heavy") }
        componentRename { enabled.set(false) }
    }
}
```

可覆盖项汇总见 [CONFIG.md → variantConfig](docs/CONFIG.md#variantconfig-createvariant--)。

## 配置模板

```kotlin
molt {
    enabledBuildTypes.set(listOf("release"))

    junkCode {
        profile.set("medium")
        // 默认不生成 Activity、不写 Manifest；需要时再开：
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



## 保护关键资源

在任意模块的 `res/raw/keep.xml` 中声明不可混淆的资源：

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@string/app_name,@layout/activity_main" />
```

- **精确条目**（`@layout/foo`）：制品中存在时保留原名
- **通配前缀**（`@drawable/ad_`*）：仅作白名单，不要求制品必含

插件会自动合并 app 与依赖 library 中的 keep 文件。

## 注意事项

1. **Release 必须开启 R8**（`isMinifyEnabled = true`），Component / View 改类名在 R8 完成后 patch DEX，无混淆则跳过。
2. **默认 buildType**：插件仅对 `alpha`、`release` 生效（`enabledBuildTypes`）；工程若无 `alpha` 可改为 `listOf("release")`；要对 debug 构建生效需加入 `debug`。
3. **Junk** `profile` **≠ Manifest**：`light/medium/heavy` 只调 utility 类数量；Manifest 变更需 `activityCountPerPackage` + `mergeJunkManifest`。
4. **keep 先于混淆**：广告 / Firebase 等 SDK 关键资源写入 `keep.xml`，并视情况开启 `verifyApkKeep` / `verifyBundleKeep`。
5. **AGP 版本**：建议 8.13.x；不一致时默认 warn，可设 `failOnAgpToolchainMismatch = true` 强制 fail。



## 示例工程

仓库 `sample/` 目录提供 app + library + flavor 的最小示例：

```bash
./gradlew -p sample :app:assembleGoogleRelease
```

详见 [sample/README.md](sample/README.md)。

## 更多文档


| 文档                                         | 受众      |
| ------------------------------------------ | ------- |
| [docs/CONFIG.md](docs/CONFIG.md)           | 全部配置项参考 |
| [sample/README.md](sample/README.md)       | 接入示例    |
| [plugin/CHANGELOG.md](plugin/CHANGELOG.md) | 版本变更    |
| [plugin/README.md](plugin/README.md)       | 插件开发与发布 |


