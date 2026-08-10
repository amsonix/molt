# Molt Roadmap / Backlog

Discussed directions not yet scheduled, ordered by value.

## Done (closed backlog items)

| Direction | Delivery |
|-----------|----------|
| Assets text encryption (declared manifest + FogAssets decode + ContentProvider init) | ✅ Shipped (4 commits): call-site rewrite (`open` / `open(String,int)`), openFd const-call auto-exclusion, AAPT no-compress media-extension intent-layer exclusion, encrypted entries forced DEFLATED (openFd always throws IOException — native method check), build warnings routed through the Gradle logger |
| DEX control-flow perturbation (junk nop injection) | ✅ Shipped (`dexPerturb`, seed-derived deterministic, works standalone without stringEncrypt) |

## Backlog (by value)

| Direction | Serves | Cost | Status |
|-----------|--------|------|--------|
| ~~Config inlining~~ (rejected: no stronger protection — decrypt functions live in the same dex and are extractable; string constants are resident in memory (GC never frees) and bloat the dex. assets encryption decrypts on demand with bounded memory; stays the only scheme) | — | — | rejected |
| Fog/FogAssets class-name randomization (auto-extraction target hardening) | Self-protection | half day | unscheduled |
| Collect remaining java.util.logging sites (AssetsProtectionEngine etc., 8 files) into Gradle logger | Warning visibility | half day | unscheduled |
| Promote cross-agp 9.3.0 probe rows to gate | Compatibility regression | 30 min | unscheduled |





## Low priority (on record)

- **PNG lossless compression** (`imagePngLosslessCompress`, pure Java: filter re-selection + Deflater level 9)
  - Positioning: **pure size optimization** — orthogonal to differentiation / anti-fingerprint (lossless does not change pixel hashes; deterministic compression keeps byte hashes identical across packages for the same source image, so it is not a differentiation mechanism)
  - Gain: 5-15% on image size; limited impact on overall APK (images are usually not the dominant part)
  - Dependencies: none (build-time pure Java); must run **before** anti-detect metadata injection
  - JPEG lossless not planned (gain <5%, needs native toolchain; existing lossy micro-compression covers it)

## Explicitly not planned

- Code virtualization (person-years of effort, commercial moat, out of scope)
- .so encryption with runtime decryption loading (third-party SDK .so loading paths are not controllable; Play review risk)
- Transparent AssetManager hook (ArtMethod replacement — AOSP-verified: entry-point offsets differ across three eras (44/28/24 on 7.0 / 8.0-11 / 12+), and 12+ ART Mainline makes the on-device ART version unpredictable; policy grey zone + unrecoverable native crashes, commercial budget item)
- 7z secondary compression (Play disallows LZMA for incremental updates; strong fingerprint signature)
- Heavy image transforms beyond existing lossy micro-compression (WebP / resource tightening belong to build configuration)
