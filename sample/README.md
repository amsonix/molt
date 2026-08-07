# Molt 最小接入示例

演示 **app + library**、**productFlavor**、**keep.xml 自动发现** 与 **Junk Code**。

## 构建

在 Molt 仓库根目录（需 `local.properties` / Android SDK）：

```bash
./gradlew -p sample :app:assembleGoogleRelease
```

或在插件模块内：

```bash
./gradlew :plugin:moltObfuscateSampleAssemble
```

## 示例配置说明

| 项 | 值 | 说明 |
|----|----|------|
| 插件来源 | `mavenLocal()` | 当前默认；先在仓库根目录执行 `./gradlew :plugin:publishToMavenLocal`。本地开发 Molt 时推荐改为 composite：`pluginManagement { includeBuild("..") }` 并去掉 `mavenLocal()` |
| Plugin ID | `io.github.amsonix.molt` | — |
| keep | `library/.../res/raw/keep.xml` | 演示跨模块自动发现 |
| 生效 BuildType | `release` | sample 仅对 release 开启 |
| Junk Code | `light` + 1 Activity | 演示 Manifest 合并 |
| 资源 / 改包 | 默认关闭 | 缩短首次构建；生产环境见根目录 README 默认配置 |

完整接入与 DSL 说明见 [../README.md](../README.md)。
