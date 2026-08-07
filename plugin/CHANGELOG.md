# Changelog

## Unreleased

### Fixed（1.1.0 发布后工作区改动，未提交；将随下一版本发布）

- Crashlytics upload 接线改为任务配置期 `matching` 匹配：**eager wiring 在 task 注册期会破坏 Crashlytics 3.x**（`generateCrashlyticsSymbolFile*` 创建时序）
- `AgpToolchainCompatibility.MIN_AGP_FOR_BUNDLE_TRANSFORM` 修正为 **8.0.2**（与兼容矩阵下限一致）
- `MoltObfuscateVariantWiring`：junk 任务 `namespace` 接线改 Provider 直传（去掉 `.get()`）
- 新增 `CrashlyticsMappingUploadWiringTest`（AGP 2.x/3.x 接线回归）
- 修复 CI（ubuntu）探针必挂：`AgpTestFixture.probeJavaHome` 搜索链漏掉标准 `JAVA_HOME`（此前仅 macOS 路径），Gradle 8.0–8.4 行在 GitHub Actions 上报 `IllegalStateException`

### Docs

- AGP 支持下限更新为 **8.0.0**：2026-08-07 单独探测 AGP 8.0.0 + Gradle 8.0（smoke / APK / AAB / rename 共 5/5 PASS）；README / COMPATIBILITY 双语同步

## 1.1.0 — 2026-08-07

### Added
- `moltPrintVariantPlan`：配置期诊断任务，打印各 variant 开关、任务名与产物路径
- `failOnCrashlyticsHookFailure`：Crashlytics upload 接线失败时可选 fail build（默认 warn）
- `failOnReleaseMinifyDisabled`：post-R8 能力开启但 `minifyEnabled=false` 时可选 fail（默认 warn）
- Feature probe 矩阵 + CI（`tools/feature-probe.sh`、`feature-probe.yml`）
- AGP 矩阵扩展至 **9.0.0 – 9.3.0**（90/90 PASS）
- Crashlytics 2.x / 3.x 探针（smoke + rename E2E）
- `CrashlyticsUploadTaskBinding`：按 task 类型缓存 `MethodHandle`，减少重复反射

### Changed
- 版本号 **1.1.0**；README / plugin README / COMPATIBILITY 同步（产物路径地图、AGP 升级 checklist、Configuration Cache 说明）
- `moltObfuscateTransformE2eTest` 等改为 feature probe 别名
- nightly 聚合：`moltObfuscateNightlyVerify` 纳入 feature probe nightly

### Fixed
- AGP 8.0.2 – 8.7.x Crashlytics 2.x stub 探针 NPE（`mappingFileProvider` 初始化 + null-safe assert）
- AGP 8.0.2 APK E2E 路径与 signing fixture
- keep 验包误报、resources-mapping ID 解析、baseline profile skip 逻辑

## 1.0.0

<details>
<summary>1.0.0 及更早变更（折叠）</summary>

## Unreleased (archived — shipped in 1.0.0 / 1.1.0)

- APK Transform 改用 AGP `SingleArtifact.APK` + `toTransformMany`（`ContainsMany` 目录 artifact）
- `failOnBaselineProfileSyncFailure`：baseline profile 重编失败可 fail build（默认 true）
- keep 验包合并 keep.xml 精确条目（通配符仍仅白名单）
- plain-text layout XML 改写（`TextXmlViewClassReplacer`）
- `moltObfuscateCheck` 别名；`check` 仅跑单元测试，nightly 独立聚合
- 单测：`MoltObfuscateArtifactVerifyTest`、`TextXmlViewClassReplacerTest`、`MoltObfuscateBaselineProfileSyncTest`

### Changed
- baseline sync skip/fail 原因统一 lifecycle/info 日志（含 AAB JdkLogger 路径）
- `verifyApkKeep`/`verifyBundleKeep` 错误提示对齐真实验包范围
- README：APK/AAB parity、CI 分层、aapt2 版本、baseline sync 前置条件

### Fixed
- keep 验包仅校验混淆**前**已存在于 APK/AAB 的资源，跳过未打包的 SDK 可选条目误报
- keep 验包范围改用 keep.xml **声明**条目，不再误含 `mergeShellKeepEntries` 注入的静态 baseline
- `resources-mapping.txt` 写入/解析 resource ID（修复日志中 `Res: null` 误导输出）
- `ResourcesMappingParser` 正确识别 APK `type/name` 增量 mapping 条目，允许跨 type 重复混淆名
- baseline profile 缺少 `baseline-prof.txt` 时统一 warn+skip（不再 fail）
- `ZipPostR8RenameProcessor` 合并 DEX zip 扫描（单次读取 dex entry）
- AAB baseline sync 误扫 `assets/` 下 dex（`ArtProfileSync.isAabModuleDexEntry`）

### Added (prior)
- WebP Tier C：VP8+VP8L 混合容器追加 `sObf` 私有 chunk
- WebP Tier D：XMP/VP8X 注入失败时回退为仅追加 `sObf`（ANIM/ALPH 等扩展容器）
- `viewRename.excludeResXmlEntryPatterns` 三方 SDK layout 跳过模板
- overlay 并行：`resourceObfuscate.overlayParallelism`（多 res 目录 + 图片）
- AAB arsc 增量：`bundleResourceObfuscate.reuseIncrementalMapping` 自动复用 `resources-mapping.txt`
- 配置期 AGP/aapt2-proto 版本漂移 warn（`AgpToolchainCompatibility`；可选 `failOnAgpToolchainMismatch`）
- 大文件 overlay fingerprint：head/middle/tail 4KiB 抽样 hash
- AAB Transform TestKit E2E（`moltObfuscateTransformBundleE2eTest`）
- `moltObfuscateApkSpotCheck` + `tools/shell-obfuscate-apk-spot-check.sh`（Firebase/广告 SDK 资源 spot check）
- nightly 纳入 sample 构建；verify 脚本支持 `RUN_HOST_SMOKE` / `RUN_APK_SPOT_CHECK`
- `variantConfig.seed` 与 resource 细项（`imageAntiDetect` / `renameXmlFiles` / PNG/JPEG 微压缩 / `incrementalOverlay`）
- overlay fingerprint 分层：≤256KiB 全量 hash
- Maven 发布：`publishMoltObfuscatePlugin` → Nexus（resource-keep + plugin marker + 实现 jar）
- `moltObfuscateSampleAssemble`：构建 sample 工程
- `resourceObfuscate.incrementalOverlay`：按 res 源目录 fingerprint 增量 skip，缩短 overlay 任务耗时
- keep.xml 自动发现 lifecycle 日志（variant + 文件路径）
- TestKit：library keep 自动发现 + overlay 保名断言；`RUN_SHELL_TRANSFORM_E2E=1` 时 APK Transform E2E
- `obfuscationMode` 配置期校验（`default` / `dir` / `file`）
- `DexIntegrationFixture`：集成测试按 variant 解析 mapping 与 intermediates APK
- `tools/shell-obfuscate-verify.sh`：Jenkins / 本地 nightly 验证入口

### Changed
- README：APK/AAB 差异表、图片 DSL、axmlStrictMode、CHANGELOG 链接
- overlay 大文件 fingerprint 由 size+mtime 改为 head/middle/tail 抽样 hash
- BinaryXmlViewClassReplacer 按 chunk 链扫描 string pool，兼容 pool 前有 resource map 的 layout

### Fixed
- Manifest 合并失败不再静默：`JunkManifestMerger` 返回 `MergeResult`，`failOnJunkManifestMergeFailure` 默认 fail build
- `verifyApkKeep`/`verifyBundleKeep` 无 baseline 时默认 fail（`failOnEmptyArtifactVerifyBaseline`）
- `mergeShrinkKeepXml` 在 afterEvaluate 校验 shrink keep 任务存在（`failOnMissingShrinkKeepTask`）
- `projectPackagePrefixes` 解析后为空时 fail

### Changed
- `variantConfig` 扩展 componentRename / viewRename.enabled 覆盖
- nightly 聚合 `moltObfuscateTransformE2eTest`；单测依赖 `compileKotlin` 缓解增量 flaky
- README：新 SDK 接入 checklist、AGP 兼容表、CI 说明
- Junk Manifest 合并改为 DOM 解析/插入（支持自闭合 `<application />`、带属性 application 标签）
- `variantConfig` 扩展：可覆盖 `resourceObfuscate.enabled`、`verify.*`、`bundleResourceObfuscate.*`
- variant 接线拆至 `MoltObfuscateVariantWiring`（Plugin 瘦身）
- `useFirebaseArtifactVerifyBaseline`：Firebase 验包 baseline 默认关，宿主显式开启
- `shrinkKeepRelativePath` / `shrinkKeepGenerateTaskName`：合并 shrink keep 不再绑定宿主 shrink-verify 插件 ID
- Junk 对齐 AndroidJunkCode：`profile` preset、`variantConfig`、Activity layout、`mergeJunkManifest`、`excludeActivityJavaFile`
- PNG/JPEG 微压缩拆分（`imagePngMicroCompress` 默认 false，`imageJpegMicroCompress` 默认 true）
- metadata token 绑定 `applicationId/variant/seed/path`
- overlay 构建期验图（`verifyImageAntiDetect`）+ 报表 `image-anti-detect-report.txt`
- APK arsc 阶段图片 metadata 兜底（`imageAntiDetectApkFallback`）
- DEX 集成测试默认读取 `app/build/intermediates/apk/<variant>/package*/`（Transform 前 APK）
- `DexSyntheticCompanionExtender` 拆分为多文件；`MoltObfuscateDescriptorWiring` 独立
- `ZipPostR8RenameProcessor`：SUPPORTED 且零替换时跳过二次 XML 解析
- 图片 anti-detect 改为无损 metadata 注入（PNG tEXt / JPEG COM / WebP XMP），移除像素扰动与 JPEG 重压缩
- 新增 `imageMicroCompress` master 开关；PNG/JPEG 分轨微压缩
- Junk Code：多子包（`packageCount`）、5 种方法模板、可选 Activity（`activityCountPerPackage`，默认 0，不写 Manifest）

### Added (prior)
- `build-logic/resource-keep` 共享模块：baseline、通配、parser、merger
- `mergeShrinkKeepXml`：自动合并 shrink-verify 生成的 keep.xml
- `verifyApkKeep` / `failOnMissingApkKeep`：APK transform 后 Firebase 等字段构建期校验
- `allowUnsignedOutput`：本地调试可跳过 APK/AAB 签名
- `axmlStrictMode`：非标准 binary XML 可配置为 fail
- Library wiring 改为完整 variant classpath 依赖图过滤
- Junk `-keep` 按 `packagePrefix` 动态生成
- `verifyBundleKeep` / `failOnMissingBundleKeep`：AAB ResourceTable keep 校验
- `moltObfuscateNightlyVerify` 聚合验证任务
- README、单测（JunkCodeGenerator、ResourceKeepMerger、AppLibraryDependencyGraph）

### Fixed
- mapping、descriptor 与资源输入按 variant 隔离，带 flavor 构建可正确接入本地 library
- 多 source set 资源只清理一次输出并共享 qualifier XML mapping
- 最终 APK/AAB 签名 Transform 禁用不完整指纹的 Build Cache，keystore 按文件内容参与 up-to-date 判断
- ResChiper `FileOperation.getZipPathFileSize` 不再使用 `available()`
- `Utils.replaceEach` 参数校验

### Removed
- `componentRename.mode=compile` 及 compile 期 Manifest/Layout/ASM Transform（仅保留 postR8）

</details>
