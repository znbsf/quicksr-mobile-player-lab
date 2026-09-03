# RIFE v4.25-lite modern-runtime probe

Date: 2026-09-03

Status: the exact current ncnn Vulkan port completed the 12-event Windows host gate and a bounded
three-level resident matrix on one Android 16 / SM8550 / Adreno 740 device. The runtime is compatible,
but the candidate failed the RIFE v4.6 replacement gate and is stopped. No file under `app/` changed.

## Bounded conclusion

Do not replace RIFE v4.6 with RIFE v4.25-lite in this project. The modern runtime removes the old
`MemoryData` blocker and runs deterministically on the observed phone, but its device result is not
a consistent mobile improvement. Relative to the frozen v4.6 matrix, the stable median is 77.2%
slower at `160x90`, 18.7% faster at `256x144`, and 56.0% slower at `320x180`. Sampled PSS rises
1.0-1.8% at all three levels. Synthetic quality regresses at the first two levels and improves at
the third.

Only the `256x144` median fits the 24 fps kernel budget, while its maximum misses it. Decode, SR,
composition, scheduling, A/V sync, final display and sustained thermal cost are still omitted.
The mixed single-device result provides neither a replacement advantage nor player headroom, so the
ncnn RIFE replacement line stops here. RIFE v4.6 remains a frozen offline baseline, not a realtime
player success.

## Exact pins and runtime correction

- TNTwise `rife-ncnn-vulkan`: `13338e38debe2e400b3eeecf6792312d01a692f9`
- ncnn submodule: `ec19da2b615cc8be438ae3d31fd34fe23df03d52`
- libwebp submodule: `5abb55823bb6196a918dd87202b2f32bbaff4c18`
- `flownet.param`: 36,221 bytes, SHA-256
  `5bd2ecebc17487798bd421476b44fe4e1730250bd91d402140cdf1ed6e23468f`
- `flownet.bin`: 11,276,252 bytes, SHA-256
  `350a15e464bea5ad378e06c0fb43996e90a0d35653d5a6ef6bc980d832538fb7`

The pinned port enables `MemoryData`, so the 2022 executable's failure is no longer relevant. The
first modern build instead exposed a shader-preamble incompatibility: `warp_pack8.comp` still uses
the legacy `afpvec8`, `buffer_ld8`, and `buffer_st8` aliases that the newer ncnn revision removed.
`rife-v425-lite-ncnn-pack8-compat.txt` restores only those aliases. Without it, online shader
compilation fails before inference; with it, both the Windows probe and Android arm64 build finish.

## Host gate

The fail-closed `anime-vfi-prefilter-v1` contract was unchanged. Exactly one distinct pair invoked
the model; the other 11 events remained bypasses or boundaries. All three runs returned zero and
produced the same output hash.

| Candidate | Three fresh-process times | Median | Peak private bytes | PSNR | Global SSIM | Edge MAE |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| RIFE v4.6 frozen baseline | 758.99 / 749.83 / 735.96 ms | 749.83 ms | 301,469,696 | 27.120 | 0.983676 | 0.009356 |
| IFRNet-S frozen rerun | 661.65 / 553.59 / 549.63 ms | 553.59 ms | 127,315,968 | 16.604 | 0.798559 | 0.015838 |
| RIFE v4.25-lite | 3704.45 / 1108.61 / 1109.12 ms | 1109.12 ms | 293,257,216 | 23.687 | 0.963678 | 0.011427 |

The first RIFE v4.25-lite process includes online shader compilation and is retained rather than
discarded. These are whole subprocess times including runtime/model startup and PNG I/O, not model
kernel latency. The fixture is synthetic and only one distinct pair is scored; human review remains
pending.

## Physical-device resident matrix

The fixture uses the same generated source pixels and prefilter decisions as the frozen v4.6
three-level subset, but records this model's actual 128-pixel padding. Each level ran one unpolled
latency process and a separate PSS/RSS-sampled process. All 14 outputs at every level matched across
the two processes, all levels returned `PASS`, and the process was absent after the matrix.

| Input -> padded | Stable model calls (ms) | Median / min / max | v4.6 median | Latency delta | PSS / RSS peak | PSNR / SSIM / edge |
| --- | --- | --- | ---: | ---: | ---: | --- |
| `160x90 -> 256x128` | 40.778 / 42.948 / 53.614 / 64.132 / 56.463 | 53.614 / 40.778 / 64.132 | 30.260 ms | +77.2% | 189,324 / 231,356 kB | 27.060 / 0.980235 / 0.011026 |
| `256x144 -> 256x256` | 31.925 / 36.066 / 36.933 / 38.102 / 47.775 | 36.933 / 31.925 / 47.775 | 45.441 ms | -18.7% | 189,266 / 231,508 kB | 26.838 / 0.980590 / 0.008769 |
| `320x180 -> 384x256` | 50.290 / 54.129 / 55.716 / 58.820 / 57.274 | 55.716 / 50.290 / 58.820 | 35.715 ms | +56.0% | 189,349 / 231,216 kB | 27.595 / 0.981523 / 0.008163 |

The v4.6 comparison uses different model-required padding (`160x96`, `256x160`, `320x192`), so the
result answers the practical candidate question rather than isolating architecture from padding.
Maximum temperature-zone proxies stayed within 44.4-45.2 C after each short run and the battery
proxy ended at 35.7 C. This is not sustained thermal evidence. The ignored raw report SHA-256 is
`bf09495e7a0b9e31ce42f6a403ae136d0c0c33eef073663d65c542fd2965933e`.

## Android build and evidence boundary

Android NDK 25.2.9519653 produced a 58,157,480-byte `arm64-v8a`, API-27 standalone binary with
SHA-256 `638568631e7b22cc6576e71275890a888757b036c29d95c94b156a4e7963a26b`.
The exact binary was subsequently executed in the matrix above. The binary, source checkout, model,
host/device outputs, and raw reports remain ignored under `local-artifacts/`.

The sanitized aggregate is `vfi-benchmark/rife-v425-lite-evidence-summary.json`. Model
redistribution stays blocked pending human rights review. The repository contains build scripts and
patches, not upstream code, weights, binaries, device identifiers, or raw logs.
