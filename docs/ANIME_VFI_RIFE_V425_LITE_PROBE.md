# RIFE v4.25-lite modern-runtime probe

Date: 2026-09-03

Status: the exact current ncnn Vulkan port completed the same 12-event Windows host gate, and its
instrumented Android arm64 CLI compiled successfully. The phone became unavailable before the
resident matrix began, so there is no SM8550/Adreno 740 execution, latency, memory, thermal, or
quality result in this cycle. No file under `app/` changed.

## Bounded conclusion

RIFE v4.25-lite remains a device-probe candidate, not a replacement. The modern runtime removes the
old `MemoryData` registration blocker and produces a deterministic host midpoint. Against frozen
RIFE v4.6, however, this fresh-process host proxy is 47.9% slower and regresses all three synthetic
quality proxies. It is much better than the stopped IFRNet-S result on that distinct pair, so the
host gate is not a catastrophic failure and does not justify stopping before a device run.

The next valid step is exactly the existing three-level resident fixture (`160x90`, `256x144`,
`320x180`) on the same SM8550/Adreno 740 class device. The generated pixels and decisions remain
identical, while the manifest records this model's actual 128-pixel padding rather than the baseline
runtime's 32. Until that is done, there is no device-side compatibility claim and no realtime or
player-integration claim.

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

## Android build and evidence boundary

Android NDK 25.2.9519653 produced a 58,157,480-byte `arm64-v8a`, API-27 standalone binary with
SHA-256 `638568631e7b22cc6576e71275890a888757b036c29d95c94b156a4e7963a26b`.
This proves cross-compilation only. The unexecuted binary, source checkout, model, host outputs, and
raw host report remain ignored under `local-artifacts/`.

The sanitized aggregate is `vfi-benchmark/rife-v425-lite-evidence-summary.json`. Model
redistribution stays blocked pending human rights review. The repository contains build scripts and
patches, not upstream code, weights, binaries, device identifiers, or raw logs.
