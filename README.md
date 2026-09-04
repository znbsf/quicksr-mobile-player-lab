# QuickSR Mobile Player Lab

**English** | [简体中文](README.zh-CN.md)

An experimental Android app for image and video super-resolution. The project follows a PC-first workflow: screen models for scale, degradation behavior, image quality, and performance on a reproducible corpus, then validate selected paths on Qualcomm QNN HTP/NPU during real playback.

> **Current result:** the primary `640×360 → 1920×1080` QuickSR QNN path sustains **30.0045 FPS processing throughput** on one physical Qualcomm device with no effect-level drops or bypasses. Final SurfaceFlinger cadence passed only **1 of 2** QNN runs, so this project does **not** yet claim guaranteed source-rate presentation.

Current version: **v0.15.0**. This is a research prototype, not a production player or a reusable AAR.

## At a glance

- Image and video super-resolution with QuickSRNetSmall 1.5×, 2×, 3×, and 4× model workflows.
- Media3/MediaCodec playback with Original, GPU Lanczos, GPU-resident Anime4K x2 Small, QuickSR CPU, and QuickSR QNN HTP modes.
- Fixed-shape QNN sessions, bounded queues, persistent tensors, frame/PTS ownership, and structured per-stage telemetry.
- An optional anime cadence-reuse experiment that preserves every source PTS and is disabled by default.
- PC-first quality evaluation across 18 routes and 72 rights-clear cases.
- Offline-only mobile probes for RIFE and IFRNet interpolation candidates; VFI is not integrated into playback.

## Current status

| Gate | Status | Evidence boundary |
| --- | --- | --- |
| Host build and checks | **PASS** | Unit tests, lint, debug APK, and Android test APK passed with locally licensed dependencies. |
| Physical-device QNN function | **DEVICE-BOUNDED PASS** | Strict HTP configuration and CPU EP fallback rejection; per-node placement is not proven. |
| 1080p processing throughput | **PASS** | 828 steady-state frames, 30.0045 FPS, source PTS coverage ≈ 1, effect drop/bypass 0/0. |
| Final 1080p display cadence | **OPEN / FAIL** | Original passed 2/2 SurfaceFlinger runs; QuickSR QNN passed 1/2. |
| A/V sync | **OPEN** | The frozen 30 FPS validation clip has no audio. |
| Thermal and power | **OPEN** | No 10–30 minute validation of the current default architecture. |
| Representative anime quality | **OPEN** | PC fixtures exist; mobile same-frame blind review is pending. |
| Anime4K x2 Small | **IMPLEMENTED / DEVICE FUNCTION** | One-device functional evidence; quality, GPU timing, and cross-device behavior remain open. |
| Frame interpolation | **OFFLINE ONLY** | Candidate CLI probes exist; no player, A/V sync, or thermal validation. |

The final SurfaceFlinger A/B/B/A comparison was:

| Run | Path | Result | Actual-present FPS | Long / short outliers |
| --- | --- | --- | ---: | ---: |
| A1 | Original | PASS | 29.9792 | 0 / 0 |
| B1 | QuickSR QNN | FAIL | 30.0341 | 1 / 2 |
| B2 | QuickSR QNN | PASS | 30.0030 | 0 / 0 |
| A2 | Original | PASS | 29.9544 | 0 / 0 |

The failed QNN run still averaged 30 FPS but contained a 58.153 ms interval followed by compensating short intervals. Average throughput and stable presentation are therefore separate gates.

## 1080p data path

```text
Media3 / MediaCodec SDR texture (source PTS)
  -> RGBA8 readback at 640x360
  -> float32 NCHW [1, 3, 360, 640] input (~2.64 MiB)
  -> QuickSRNetSmall 3x on QNN HTP
  -> two pinned float32 NHWC [1, 1080, 1920, 3] outputs (~23.73 MiB each)
  -> deferred copy + four-stripe NHWC-to-RGBA8 packing
  -> direct upload to the 1920x1080 Media3 output texture
  -> SurfaceFlinger with the original PTS
```

The pipeline overlaps inference for frame N+1, post-processing for frame N, and GL delivery for frame N−1. Admission remains bounded to two frames; it does not use an unbounded queue to manufacture apparent throughput.

### Video profiles

| Profile | Neural path | Role |
| --- | --- | --- |
| 720p | `640×360 → 1280×720`, 2× | Diagnostic and regression baseline |
| 1080p | `640×360 → 1920×1080`, 3× | Primary mobile product gate |
| 1440p | `640×360 → 2560×1440`, 4× | High-resolution experiment |
| 4K display | `640×360 → neural 1920×1080 → GPU 3840×2160` | Display fallback, **not** native neural 4K |

The image path performs full-image 2× upscaling with CPU or QNN and can tile work to stay within memory limits. It has a different performance contract from the fixed-shape video path.

## Engineering learning report

The project progressed from proving that QNN could execute, through a sub-20 FPS implementation, to a 30 FPS average processing pipeline. The figures below are bounded to the recorded device, APK, model, clip, and measurement definition.

### Changes kept in the default path

| Change | Why it stayed |
| --- | --- |
| Fixed-shape models, hashes, persistent sessions and tensors | Makes the model/runtime identity reproducible and removes per-frame setup. |
| Strict QNN HTP configuration | Fails closed instead of silently treating CPU fallback as NPU evidence. |
| Three-stage bounded pipeline | Early stage observations improved from about 11.435 FPS serial to 17.960 FPS with overlap; later changes closed the remaining gap. |
| Float32 NHWC output and four packing stripes | Contiguous RGB access and fixed row ownership reduced output conversion cost; two stripes were slower. |
| Two pinned ORT outputs with deferred copy | In same-device A/B/B/A, inference caller p95 fell from 34.733 to 30.529 ms on average, at the cost of ~23.73 MiB. |
| Full finite-value scan only on the first output | Removed a periodic 330–353 ms steady-state diagnostic pause while preserving fail-closed session validation. |
| Direct upload to the same-size Media3 output texture | Removed an intermediate texture and scale blit; one observation reduced GL submit p95 from 6.435 to 3.460 ms. |

Final steady-state measurements for the default 1080p path:

| Metric | Result |
| --- | ---: |
| Measured frames | 828 |
| Effect output-submit throughput | 30.0045 FPS |
| QNN caller p50 / p95 | 27.884 / 30.736 ms |
| ORT run p95 | 30.244 ms |
| NHWC→RGBA pack p50 / p95 | 11.321 / 15.503 ms |
| GL upload submit proxy p95 | 6.987 ms |
| Accepted→output-submit proxy p95 | 179.024 ms |
| Effect drop / bypass | 0 / 0 |
| Maximum bounded queue depth | 2 |

The 179.024 ms figure is multi-frame pipeline latency, not single-frame service time. Once filled, the pipeline can still deliver at the measured 30 FPS rate.

### Experiments not enabled by default

| Experiment | Decision |
| --- | --- |
| JNI/arm64 NEON output packer | Stopped: an earlier A/B/B/A moved pack p50 from 36.566 to 102.353 ms and reduced average FPS by 42.94%. |
| Direct FloatBuffer-to-native packing | Stopped: measured around 116.5 ms. Fewer copies did not offset access and JNI costs. |
| Java direct element-wise buffers | Stopped for the hot loop: slower than sequential heap access in host/emulator probes. |
| Two packing stripes | Stopped: slower than four stripes on device. |
| Larger queues or multiple workers on one QNN session | Rejected: they add latency or ownership hazards without increasing the graph service rate. |
| Temporal batch 2 | Not integrated: host-only gains of roughly 4.2–16.9% add a frame of wait and a larger output. |
| Spatial batching | Not integrated: host results ranged from −2.3% to +0.7%. |
| Double PBO upload | Research switch only: GL submit p95 improved to 1.832 ms, but display cadence remained 1/2 PASS and memory grew by ~15.82 MiB. |
| Anime cadence reuse | Benchmark switch only: a frozen 720p mapping reduced inference count by 37.79%, but subtitle, motion, and scene-cut safety are not validated. |

The central lesson is that local timing improvements do not automatically improve the final consumer. The next useful experiment must correlate one failed frame ID/PTS with Perfetto scheduling/frequency data, GL fences, BufferQueue, and SurfaceFlinger FrameTimeline. Only then should the implementation change the domain that actually caused the missed latch.

## Quality and model direction

Across 72 rights-clear PC cases, QuickSR won PSNR on 30/36 clean inputs, only 7/36 blur-plus-JPEG-Q35 inputs, and 16/18 square manga cases. This suggests that QuickSRNetSmall fits clean line art and manga better than severe blur/compression, where Lanczos fallback or a degradation-trained model may be preferable.

These cases are not representative of all commercial anime, subtitles, grain, codecs, or devices. Mobile same-source, same-frame blind review must happen after the frame-rate gate. If QuickSR does not show a stable visible advantage, the next model path is GPU-resident Anime4K or a lighter anime-specific candidate such as SESR-M5—not simultaneous model and architecture churn.

RIFE, IFRNet, and ANVIL-class interpolation research remains later work. Interpolation adds inference load and cannot solve the requirement that super-resolution first preserve the source frame rate.

## Build and run

Prerequisites:

- Windows PowerShell, Android Studio or Android SDK, and Java 17+.
- A legally obtained QuickSRNet model whose SHA-256 matches the local manifest.
- Independent license review before packaging Qualcomm runtime binaries.

Prepare local models using [models/README.md](models/README.md). Model weights and fixed-shape binaries are intentionally absent from Git; missing or mismatched assets fail the build.

Build an arm64/QNN debug APK:

```powershell
.\build-local.ps1
```

Or point the build at a local canonical 2× ONNX model:

```powershell
.\build-local.ps1 -ModelPath <local-canonical-onnx>
```

For the x86_64 emulator CPU path, see [the PC benchmark guide](pc-benchmark/README.md) and the detailed commands in the [Chinese README](README.zh-CN.md#构建与运行).

In the app, select an output profile and backend, then choose a local non-DRM SDR video. Treat 720p as diagnostic, 1080p as the primary gate, and 1440p/4K display as experiments that require memory and thermal observation.

## Evidence and documentation

- [Edge model deployment learning report (Chinese, with PlantUML diagrams)](docs/EDGE_MODEL_DEPLOYMENT_LEARNING_GUIDE.zh-CN.md)
- [Current project status](docs/STATUS.md)
- [Implementation plan and progress](docs/IMPLEMENTATION_PLAN_AND_PROGRESS.md)
- [Real-time architecture optimization audit](docs/REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md)
- [Machine-readable 1080p physical-device summary](docs/evidence/realtime-1080p-physical-20260905.json)
- [Anime4K Android GPU integration](docs/ANIME4K_ANDROID_GPU_INTEGRATION.md)
- [Anime cadence reuse contract](docs/ANIME_CADENCE_REUSE.md)
- [Anime SR and VFI research plan](docs/ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)
- [PC benchmark guide](pc-benchmark/README.md)
- [Physical-device QNN validation](docs/ANDROID_MOBILE_SUBSET_VALIDATION.md)

## Platform scope

| Environment | Scope |
| --- | --- |
| Android 8.1+, arm64-v8a, compatible Qualcomm HTP/CDSP | Target QNN platform; evidence is currently limited to one physical device. |
| Android 8.1+, other arm64-v8a devices | CPU and GPU paths can be built; no broad compatibility matrix exists. |
| Android Studio API 35 x86_64 emulator | Functional CPU/GPU checks only; never counted as QNN HTP performance evidence. |
| armeabi-v7a, 32-bit x86, iOS | Unsupported. |

The current media scope is local, SDR, and non-DRM. HDR, DRM, live streams, complex subtitle pipelines, cross-vendor behavior, and long-duration thermal stability are not validated.

## Upstream and pinned stack

| Component | Version / upstream | Use |
| --- | --- | --- |
| QuickSRNetSmall | [Qualcomm AIMET Model Zoo](https://github.com/quic/aimet-model-zoo/tree/develop/aimet_zoo_torch/quicksrnet) | 1.5×/2×/3×/4× checkpoints |
| QuickSRNet reference | [Qualcomm AI Hub Models](https://github.com/qualcomm/ai-hub-models) | Architecture and mobile reference |
| AndroidX Media3 | `1.11.0` / [androidx/media](https://github.com/androidx/media) | Player, MediaCodec, GL effects, Lanczos baseline |
| ONNX Runtime Android | `1.26.0` / [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | CPU inference and Java API |
| ONNX Runtime QNN plugin | `2.5.0` / [onnxruntime-qnn](https://github.com/microsoft/onnxruntime/tree/main/onnxruntime/core/providers/qnn) | QNN Execution Provider |
| Qualcomm QNN Runtime | `2.49.0` / [Maven metadata](https://central.sonatype.com/artifact/com.qualcomm.qti/qnn-runtime/2.49.0) | HTP/CDSP runtime dependency |
| Android Gradle Plugin / Gradle | `8.13.1` / `8.14` | Android build |
| compileSdk / targetSdk / minSdk | `36 / 35 / 27` | Android platform levels |

Exact revisions, hashes, notices, and redistribution boundaries are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Source-only publication policy

Before publishing a change, run:

```powershell
.\scripts\verify-publication.ps1
```

The repository may contain original source, tests, source references, hashes, machine-readable plans, and de-identified summaries. It must not contain model checkpoints, ONNX binaries, APK/AAB/AAR files, vendor libraries, private media, device identifiers, raw logs/traces/tensors, signing material, or credentials.

The only neural shader stored in source is the separately reviewed Anime4K x2 Small text, pinned by upstream commit, bytes, and SHA-256 with its MIT notice preserved. Passing the scanner does not grant redistribution rights for models, datasets, Qualcomm runtime binaries, or app packages. See [the publication boundary](docs/PUBLICATION_BOUNDARY.md).

## Known limitations

- Results do not establish real-time behavior on every Qualcomm phone.
- The 4K profile is GPU-scaled neural 1080p, not native neural 4K.
- MediaCodec render/drop counters and app output-submit timing do not prove final display latch or end-to-end A/V sync.
- The current corpus does not represent every anime style, subtitle pattern, degradation, codec, or cadence defect.
- The project is not yet packaged as a stable library.

See the [player roadmap](docs/PLAYER_ROADMAP.md) for the broader direction and [real-time video SR lessons](docs/REALTIME_VIDEO_SR_LESSONS.md) for the extended engineering record.
