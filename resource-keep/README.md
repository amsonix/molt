# resource-keep

molt 插件与 shrink-resources 共用的 keep 基础设施。

## 模块职责

| 类型 | 类 | 说明 |
|------|-----|------|
| 数据模型 | `ResourceKeepResource` | `type` + `name` 资源条目 |
| 解析 | `ResourceKeepParser` | 解析 `res/raw/keep.xml` 中 `tools:keep` |
| 合并 | `ResourceKeepMerger` | 合并 declared / dynamic / baseline / 通配 |
| 通配 | `ResourceKeepBuiltinWildcards` | 广告 SDK 前缀（`tt_` / `mbridge_` / `sdm_` 等） |
| 静态兜底 | `ResourceKeepStaticBaseline` | Firebase / 系统 / 广告 SDK 精确资源名 |
| 验包 baseline | `ResourceKeepStaticBaseline.artifactVerifyRequired` | Firebase 构建期必含字段 |

## keep 语义

- **精确条目**：若资源存在于制品中，混淆时保留原名
- **通配条目**：仅参与白名单，不要求 APK/AAB 必含
- **artifactVerifyRequired**：Firebase 集成工程的构建期验包 baseline

## 消费方

- **molt** 插件：`KeepXmlParser`、`MoltObfuscateArtifactVerify`、overlay keep 合并
- shrink-verify / 其他 shrink 插件：通过 `ResourceKeepParser` 读取 keep.xml

## 发布

随 **molt** 插件一同发布：

`io.github.amsonix.molt:resource-keep:<version>`
