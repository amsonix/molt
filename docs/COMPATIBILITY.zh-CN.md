# AGP 兼容性

English: [COMPATIBILITY.md](COMPATIBILITY.md)

## 支持范围（探测结论）

| 能力 | AGP 范围 |
|------|----------|
| 全链路（APK/AAB transform + rename） | **8.0.2 – 9.3.0** |

2026-08-06 本地矩阵：**18 档 × 5 探针 = 90/90 PASS**。

## 运行探测

```bash
./tools/agp-compat.sh
```

**环境要求：** JDK 17–21；AGP 9.x 对应 Gradle 9.x。若 shell 里 `MOLT_PROBE_JAVA_HOME` 指向 JDK 24，脚本会忽略并自动选 Temurin 17（可 `unset MOLT_PROBE_JAVA_HOME` 去掉 WARN）。

## Gradle 配对

| AGP | Gradle |
|-----|--------|
| 8.0.2 – 8.13.2 | 见 [matrix](../tools/agp-compat-matrix.txt) |
| 9.0.0 | 9.1 |
| 9.1.1 | 9.3.1 |
| 9.2.0 | 9.4.1 |
| 9.3.0 | 9.5 |

## CI

18 行 AGP 并行；**8.13.2** 拦 PR。功能维度 probe 设计见 [FEATURE_PROBE.md](FEATURE_PROBE.md)。

## 升级 AGP（checklist）

| 步骤 | 命令 / 操作 |
|------|-------------|
| 1. 矩阵 smoke | `./tools/agp-compat.sh` 或 CI |
| 2. Feature gate | `FEATURE_PROBE_TIER=gate ./tools/feature-probe.sh` |
| 3. Pin 漂移 | 升级 PR 中临时 `failOnAgpToolchainMismatch = true` |
| 4. Nightly（可选） | `./tools/molt-verify.sh` |
| 5. 宿主验证 | `:app:moltPrintVariantPlan` + 检查 `shell-obfuscate-mapping.txt` |

## Configuration Cache

Molt 任务与 Provider 接线兼容 Gradle **Configuration Cache**。宿主工程可在 `gradle.properties` 开启：

```properties
org.gradle.configuration-cache=true
```

AGP 矩阵探针尚未单独 gate CC；Gradle 8.5+ 宿主建议在本地验证。
