# Molt Gradle Plugin

插件实现模块。完整文档见仓库根目录 [README.md](../README.md)。

## 快速命令

```bash
# 单元测试
./gradlew :plugin:moltObfuscateCheck

# 示例 App
./gradlew :plugin:moltObfuscateSampleAssemble

# 发布（需 Nexus 凭证）
./gradlew :plugin:publishMoltObfuscatePlugin
```

## 接入

```kotlin
plugins {
    id("io.github.amsonix.molt")
}

molt {
    junkCode { profile.set("light") }
}
```
