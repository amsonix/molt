# Molt 配置参考

> 中文 | English: [CONFIG.md](CONFIG.md)

[`molt { }`](../README.zh-CN.md#常用配置) 扩展块**全部**公开配置项。日常接入只需 README 中的常用子块；验包、`failOn*`、图片 overlay 细项等在此查阅。

未列出的 `variantConfig` 可覆盖项见各表「variant 可覆盖」列及文末汇总表。

## 顶层

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | `Boolean` | `true` | 插件总开关 |
| `enabledBuildTypes` | `List<String>` | `release` | 仅对列出的 buildType 生效；需要时可加入 `debug`、`alpha` 等自定义 buildType |
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
| `failOnCrashlyticsHookFailure` | `Boolean` | `false` | Crashlytics upload 接线失败时 fail build |
| `failOnReleaseMinifyDisabled` | `Boolean` | `false` | post-R8 能力开启但 `minifyEnabled=false` 时 fail build |
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

## `junkCode { }`

编译期在独立源码目录生成 Junk 代码，参与 Java 编译并打进 DEX。

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | Junk Code 开关 | ✓ |
| `profile` | `String` | `light` | utility 类量级 preset | ✓ |
| `packageCount` | `Int` | `5` | 子包数量（`custom` 时生效） | ✓ |
| `classCount` | `Int` | `30` | utility 类总数，**不含** Activity（`custom` 时生效） | ✓ |
| `methodsPerClass` | `Int` | `8` | 每类方法数（`custom` 时生效） | ✓ |
| `activityCountPerPackage` | `Int` | `0` | 每子包生成的 Activity 数；`0` = 不生成组件 | ✓ |
| `excludeActivityJavaFile` | `Boolean` | `false` | 跳过 Activity `.java`，仍生成 layout / Manifest 片段 | ✓ |
| `mergeJunkManifest` | `Boolean` | `false` | 合并 Junk Activity 到 app Manifest（需 `activityCountPerPackage > 0`） | ✓ |
| `resPrefix` | `String` | `junk_` | Activity layout 资源名前缀 | ✓ |
| `packagePrefix` | `String` | `{applicationId}.shell.junk` | Junk 类包名前缀 | — |

### `profile` preset（仅 utility 类）

| profile | 子包数 | utility 类数 | 每类方法数 |
|---------|--------|--------------|------------|
| `light` | 5 | 30 | 8 |
| `medium` | 10 | 100 | 12 |
| `heavy` | 30 | 1500 | 20 |
| `custom` | 使用 `packageCount` / `classCount` / `methodsPerClass` | | |

## `resourceObfuscate { }`

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
| `imagePerceptualNoise` | `Boolean` | `false` | LSB 微扰动（抗感知哈希比对） | — |
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

## `bundleResourceObfuscate { }`

APK / AAB 内 `resources.arsc` 与 res 路径混淆。

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | AAB 资源表混淆 | ✓ |
| `obfuscateApk` | `Boolean` | `true` | APK 资源表混淆 | ✓ |
| `obfuscationMode` | `String` | `default` | 混淆模式：`default` / `dir` / `file` | — |
| `mappingFile` | `File` | 自动生成 | 增量复用的 `resources-mapping.txt` | — |
| `reuseIncrementalMapping` | `Boolean` | `true` | 自动复用上次 Transform 的 mapping。**注意**：开启时修改 `seed` **不会**重新随机化资源名（已有条目保留旧名，仅 junk / 组件 / View 名跟随新 seed）；删除 `build/shell-obfuscate/<variant>/{apk,bundle}-resource/resources-mapping.txt` 可强制全量重滚 | — |

## `componentRename { }`

R8 完成后，将 Manifest / layout / navigation 等引用的组件完整类名（FQCN）映射为随机短名，并 patch DEX。

```
com.shortvideo.playlet.SplashActivity  →  e3.gj1
com.shortvideo.playlet.MainService     →  z0.re4.hu6
```

类名 simple name **也会变**，不是「只换包名」。

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | Activity / Service / Receiver / Provider 改类名 | ✓ |
| `excludePatterns` | `List<String>` | `*.debug.*`, `*Hilt_*`, `*_HiltModules*` | 不参与改名的 FQCN glob | — |

## `viewRename { }`

R8 完成后，替换 layout / navigation XML 中**自定义 View** 的类名（系统 / AndroidX 控件不改）。

| 选项 | 类型 | 默认值 | 说明 | variant 可覆盖 |
|------|------|--------|------|----------------|
| `enabled` | `Boolean` | `true` | 自定义 View 改类名 | ✓ |
| `excludePatterns` | `List<String>` | `*.debug.*`, `*Hilt_*`, `*_HiltModules*` | 不参与改名的类名 glob | — |
| `excludeResXmlEntryPatterns` | `List<String>` | 内置广告 SDK layout 规则 | 跳过改写的 layout 路径 glob | — |

## `variantConfig { create("<variant>") { } }`

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
