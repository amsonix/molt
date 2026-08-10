# Molt Roadmap / Backlog

Discussed directions not yet scheduled, ordered by value.

## Backlog (by value)

| Direction | Serves | Cost | Status |
|-----------|--------|------|--------|

| Assets text encryption tier (declared manifest + Fog key + runtime decode helper) | Resource anti-extraction | 1 day | unscheduled |
| DEX control-flow perturbation (basic-block reorder / junk instructions / reflection wrapping) | Reverse-engineering resistance / differentiation | 2-3 days | unscheduled |


| Image anti-detect transform verify gap (fallback injection not recorded in report; only decodability checked) | Verification closure | half day | unscheduled |

## Low priority (on record)

- **PNG lossless compression** (`imagePngLosslessCompress`, pure Java: filter re-selection + Deflater level 9)
  - Positioning: **pure size optimization** — orthogonal to differentiation / anti-fingerprint (lossless does not change pixel hashes; deterministic compression keeps byte hashes identical across packages for the same source image, so it is not a differentiation mechanism)
  - Gain: 5-15% on image size; limited impact on overall APK (images are usually not the dominant part)
  - Dependencies: none (build-time pure Java); must run **before** anti-detect metadata injection
  - JPEG lossless not planned (gain <5%, needs native toolchain; existing lossy micro-compression covers it)

## Explicitly not planned

- Code virtualization (person-years of effort, commercial moat, out of scope)
- .so encryption with runtime decryption loading (third-party SDK .so loading paths are not controllable; Play review risk)
- Transparent AssetManager hook (ArtMethod replacement — no policy prohibition, but version-compatibility maintenance cost is a commercial budget item)
- 7z secondary compression (Play disallows LZMA for incremental updates; strong fingerprint signature)
- Heavy image transforms beyond existing lossy micro-compression (WebP / resource tightening belong to build configuration)
