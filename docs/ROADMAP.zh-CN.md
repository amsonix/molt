# Molt Roadmap / Backlog

记录已讨论但尚未排期的方向，按优先级排序。

## 已完成（历史 Backlog 闭环）

| 方向 | 落地 |
|------|------|
| assets 文本加密档（声明清单 + FogAssets 解密 + ContentProvider 初始化） | ✅ 已完成（4 个提交迭代）：调用点改写（`open`/`open(String,int)`）、openFd 常量调用自动排除、AAPT no-compress 媒体扩展名意图层排除、加密条目强制 DEFLATED（openFd 遇压缩必抛 IOException 免疫）、构建期告警经 Gradle logger 输出 |
| DEX 控制流扰动（junk nop 注入） | ✅ 已完成（`dexPerturb`，seed 派生确定性，独立于 stringEncrypt 可单独启用） |
| Fog/FogAssets 类名随机化（防自动化提取靶子） | ✅ 已完成（1.2.0）：解密类名由 seed 派生（`fogClassName` / `fogAssetsClassName`），每次构建不同；keep 规则按精确 applicationId 前缀生成 |

## Backlog（按价值排序）

| 方向 | 服务定位 | 成本 | 状态 |
|------|----------|------|------|
| ~~配置内联化~~（已否决：保护强度不增——解密函数同在 dex 可提取；字符串常量常驻内存 GC 不回收、dex 膨胀。assets 加密按需解密内存可控，保留为唯一方案） | — | — | 已否决 |
| 全插件 java.util.logging → Gradle logger 收编（AssetsProtectionEngine 等 8 处） | 告警可见性 | 半天 | 待排期 |
| cross-agp 9.3.0 探针行升 gate | 兼容性回归 | 半小时 | 待排期 |





## 低优先（记录在案）

- **PNG 无损压缩**（`imagePngLosslessCompress`，纯 Java：filter 重选 + Deflater level 9）
  - 定位：**纯体积优化**，与差异化/抗指纹正交（无损不改变像素 hash；确定性压缩下同源图字节 hash 包间仍一致，不构成差异化手段）
  - 收益：图片体积 -5~15%；对整体 APK 影响有限（图片通常非大头）
  - 依赖：无（构建期纯 Java）；执行顺序需在 anti-detect metadata 注入**之前**
  - 不做 JPEG 无损（收益 <5%，需 native 工具链，现有有损微压缩已覆盖）

## 明确不做的方向

- 代码虚拟化（数人年工作量，商业护城河，非自研范围）
- so 加密 + 运行时解密加载（第三方 SDK 的 so 解密流程不受控，Play 审查风险）
- 透明 AssetManager hook（ArtMethod 替换——AOSP 实证：entry_point 偏移在 7.0 / 8.0-11 / 12+ 三个时代为 44 / 28 / 24 三套，且 12+ ART Mainline 化使设备实际 ART 版本不可预测；政策灰色 + native crash 不可恢复，属商业产品预算）
- 7z 二次压缩（Play 禁用 LZMA 增量更新，且是强指纹特征）
- PNG/JPEG 有损微压缩之外的重度图片变换（由 WebP/资源收紧等构建配置承担）
