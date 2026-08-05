# Molt 最小接入示例

演示 **app + library**、**productFlavor**、**keep.xml 自动发现** 与 **Junk Code**。

## 构建

在 Molt 仓库根目录（需 `local.properties` / Android SDK）：

```bash
./gradlew -p plugin/sample :app:assembleGoogleRelease
```

或在插件模块内：

```bash
./gradlew :plugin:moltObfuscateSampleAssemble
```

## 说明

| 项 | 示例配置 |
|----|----------|
| 插件来源 | `includeBuild("../..")` 引用 Molt 根工程 |
| Plugin ID | `io.github.amsonix.molt` |
| keep | `library/src/main/res/raw/keep.xml` |
| DSL | `molt { }` |

完整能力见 [../../README.md](../../README.md)。
