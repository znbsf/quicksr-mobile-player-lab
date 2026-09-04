# Anime model laboratory report

Status date: 2026-09-04

This report covers frame-by-frame anime super-resolution candidates in the
independent model-lab worktree based on `ad7e1cb`. It does not cover frame
interpolation and does not modify or validate the Media3/QNN playback hot path.

This is a candidate-selection report, not the current integration dashboard. Anime4K x2 Small
subsequently entered the player and obtained bounded single-device model-active function evidence;
fixed-frame output equivalence, representative anime quality, GPU timing and sustained behavior
remain open. Current sequencing is owned by `IMPLEMENTATION_PLAN_AND_PROGRESS.md`.

## Outcome

Exactly two candidates advance to the next evidence gate:

- GPU shader: **Anime4K v4.0.1 Upscale CNN x2 Small**.
- Mobile neural model: **SESR-M5 2x** from the pinned Qualcomm AI Hub recipe.

“Advance” means that source preparation, PC output comparison and a separate
Android device experiment are justified. It is not an integration decision,
realtime claim, quality win, thermal result, or redistribution approval.

The machine-readable evidence and decisions are frozen in
`pc-benchmark/anime-model-candidates.json`. The validator requires one and only
one promoted GPU shader and one promoted mobile SISR model. A name containing
“video” is not treated as evidence of temporal processing.

## Independent evidence matrix

| Candidate | Source / version / code license | Weights | Training or evaluation data | I/O and size | PC / Android path | Decision |
| --- | --- | --- | --- | --- | --- | --- |
| Anime4K x2 Small | [`bloc97/Anime4K`](https://github.com/bloc97/Anime4K) `v4.0.1`, commit `4029bf701ecaa15f163cdc49cffe5501c1acf410`, MIT | Embedded in the MIT shader; URL, 18,638 bytes and SHA-256 frozen | Stable shader training corpus not identified upstream | Dynamic RGB texture, native 2x RGB; 1,384 parameters derived from pinned layer descriptors; five hook passes; peak memory open | mpv/libplacebo GLSL on PC; subsequently integrated into Android Media3/GLES with bounded single-device function evidence; fixed-frame quality/GPU timing still open | **Advance GPU** |
| Anime4K x2 Medium | Same pinned MIT release | Embedded in shader; URL, 37,685 bytes and SHA-256 frozen | Same provenance gap | Dynamic RGB texture, native 2x RGB; 2,548 parameters published by the pinned generator; nine hook passes; peak memory open | Same, but higher pass count | Hold as quality A/B after Small |
| FSRCNNX 8-0-4-1 | [`igv/FSRCNN-TensorFlow`](https://github.com/igv/FSRCNN-TensorFlow) release 1.1, tag commit `1aa11ab0e1fc12741fdb84cef31da5619a478670`; repository has GPL and MIT files without a mapping | Small release shader has its own LGPL-3.0-or-later header; LineArt is inside a separate archive without an independently frozen license/hash here | General-100 and classic test sets are mentioned without a rights chain suitable for this task | LUMA shader / NHWC single-channel training graph; native 2x; Small is approximately 2,948 parameters from source; memory open | mpv shader; no validated Android port | Blocked |
| `realesr-animevideov3` | [`xinntao/Real-ESRGAN`](https://github.com/xinntao/Real-ESRGAN) v0.2.5.0 tag commit `685d429c81888252bdb10f56c7754baededc3823`, BSD-3-Clause; ncnn wrapper is MIT | v0.2.5.0 checkpoint and ncnn model have no separate weight license established here | AnimeVideo-v3 training data are not disclosed | Per-frame NCHW RGB, native 4x; x1/x2/x3 are output resize; 621,424 parameters derived from the pinned 16-convolution SRVGG definition; Android peak open | PyTorch or ncnn Vulkan; no project-device evidence | Blocked |
| Real-CUGAN | [`bilibili/ailab`](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) commit `2799af78ef105b414cc4b796c67c8511acdcdf6f`, MIT | PyTorch/ncnn weights lack an independently established weight license | Upstream says million-scale anime patches but does not disclose asset identities or rights | Per-frame RGB, 2x/3x/4x; parameters and Android peak open | PyTorch / third-party ncnn Vulkan; no project-device evidence | Blocked |
| SESR-M5 2x | [`qualcomm/ai-hub-models`](https://github.com/qualcomm/ai-hub-models/tree/main/src/qai_hub_models/models/sesr_m5) commit `b703d5bed55658f85d9259d596a984e81fb4a986`, BSD-3-Clause; original Arm code Apache-2.0 | AIMET Model Zoo checkpoint is pinned to release-tag commit `59640d130992f984fe71339c27221aa6e3434aef`, URL, 4,028,066 bytes, SHA-256 and BSD-3-Clause model card | Trained on [DIV2K](https://data.vision.ee.ethz.ch/cvl/DIV2K/), whose official page limits the dataset to academic research | Source recipe: NCHW float RGB `[0,1]`, native 2x; 343K parameters; model card says 1.32 MB float / 395 KB W8A8 | Qualcomm recipe supports edge export; ORT/QNN or LiteRT/QNN delegate needs a new export and device proof | **Advance mobile neural** |
| Mobile RRN | [`MediaTek-NeuroPilot/mai22-real-time-video-sr`](https://github.com/MediaTek-NeuroPilot/mai22-real-time-video-sr) commit `f49d6f56f7eb8f86fcbacdb22487f5638766ab87`, Apache-2.0 | No hash-pinned official checkpoint established | REDS challenge data, registration/terms required | NHWC frame pair `[1,320,180,6]` plus state `[1,320,180,16]`; recurrent 4x | TensorFlow/TFLite with MediaTek Neuron delegate reference | Temporal architecture reference only |
| FANI | [`kyrie2to11/FANI`](https://github.com/kyrie2to11/FANI) commit `4286315b446de995aacb3fd7463d3a5ed39d96fb`, MIT | No hash-pinned official checkpoint established | REDS; terms remain a separate gate | Multi-frame feature aggregation; exact deploy contract and peak open | TensorFlow 2.11 / TFLite reference | Temporal architecture reference only |
| AnimeSR | [`TencentARC/AnimeSR`](https://github.com/TencentARC/AnimeSR) commit `80a24bf7a5270907c703158ff6affb3248bf0dc2`, Apache-2.0 | Google Drive checkpoints lack frozen byte/hash and separate weight evidence here | AVC requires acceptance of a custom agreement | Frame sequence, native 4x; other scales post-resize | PyTorch/CUDA; no official mobile path | Quality/training reference only |
| VQD-SR | [`researchmm/VQD-SR`](https://github.com/researchmm/VQD-SR) commit `d7fd5e0d97ef08c5c329e3c55870899f7cd9f473`; no top-level license found | Google Drive checkpoints lack frozen byte/hash and license evidence | RAL and AVC require request agreements | Temporal VSR plus learned degradation prior; mobile peak open | Eight-V100 training recipe; no official mobile path | Quality/training reference only |

## What is confirmed

- The current worktree contains `ad7e1cb` as an ancestor and the audit started
  from a clean detached `main` baseline.
- Anime4K v4.0.1 Small and Medium shader bytes were independently downloaded
  from commit-addressed raw URLs and hashed before being added to the
  source-only allowlist.
- A subsequent v0.15.0 source integration retained the exact MIT Small text,
  parses all five passes into a GPU-resident Media3 effect, and passes host
  source-identity, dimension and Java compilation tests. A later bounded run on one
  Android 16 / Adreno 740 device produced model-active function samples at 720p,
  1080p and 1440p without fallback; it still did not produce the fixed paired output,
  GPU completion timing, representative visual review or sustained evidence required
  for promotion.
- The SESR-M5 2x checkpoint was downloaded to ignored local cache, hashed, and
  inspected only as a container; it was not deserialized, exported, committed,
  or added to the Android app.
- Qualcomm’s pinned super-resolution template declares NCHW float32 RGB input
  and output in `[0,1]`, dynamic image dimensions, and runtime channel
  reordering. Its published SESR-M5 card reports 343K parameters and model
  license BSD-3-Clause.
- Real-ESRGAN’s AnimeVideo-v3 code instantiates a 16-body-convolution
  `SRVGGNetCompact` and processes extracted frames independently. “Video” is a
  usage label, not a temporal-state claim.
- The existing project benchmark already freezes clean Lanczos and blur 0.6 +
  JPEG Q35, uses CC BY animation/comic assets, preserves square aspect, and
  reports PSNR, global SSIM and edge MAE. The prior 72-case QuickSR aggregate is
  reusable as the baseline; it is not a result for any new candidate.

## Derived or inferred

- Anime4K Small’s 1,384 parameters and FSRCNNX Small’s approximately 2,948
  parameters are arithmetic derived from pinned layer descriptions/source, not
  upstream model-card numbers.
- Anime4K Small is expected to have lower mobile risk than Medium because it has
  five rather than nine shader hook passes. Actual GLES compiler behavior,
  texture formats, allocation peaks and GPU time remain unmeasured.
- SESR-M5 is the stronger mobile-neural staging choice because the source,
  checkpoint coordinate, compact parameter count and Qualcomm export route are
  all identifiable. This does not imply that it will beat QuickSR or
  AnimeVideo-v3 on the rights-clear anime-style corpus.

## License and provenance gates still open

- Anime4K does not identify the training corpus for the stable embedded
  weights. The shader file is MIT-licensed, but a human release review still
  owns the training-provenance question.
- DIV2K is explicitly academic-research-only and its images retain original
  copyrights. SESR local research can proceed under that boundary, but APK,
  checkpoint or public model distribution needs a separate legal decision.
- FSRCNNX repository-wide license mapping, LineArt archive license/hash and
  training-data rights are incomplete.
- AnimeVideo-v3 and Real-CUGAN weight/data rights are incomplete; therefore no
  download manifest or runner was added for them.
- AVC, RAL and REDS agreement/registration paths were not accepted or used.

## Recomputable benchmark

The current contract generates three original 1280x720 frames: thin/thick line art,
high-contrast pixel subtitles and deliberately low-contrast pixel subtitles. It creates the same
two project degradations for all three sources. Optional open-asset mode adds the already allowlisted
Pepper&Carrot wide and square fixtures. Candidate tooling must write one exact
RGB 2x PNG per case; evaluation fails closed on missing files, wrong mode or
wrong dimensions and compares each output with Lanczos using the existing
PSNR/global-SSIM/edge-MAE implementation.

This run prepared and validated the synthetic contract and evaluator. It does
not produce candidate quality or timing numbers because the Anime4K adapter
has not run on a GL device and SESR export was not performed. That is an explicit
unmeasured result, not a failed quality comparison.

The initial, now superseded two-case contract SHA-256 was
`64dae5f2c2a551053dc2c2a078fa3ac276cb6ff135b03b299c2ede3f9443e121` on
Python 3.12.3, NumPy 2.2.6 and Pillow 12.3.0. A protocol-only run copied the
Lanczos baseline into the candidate output directory and reproduced identical
hashes and metrics, as required:

| Fixture | PSNR | Global SSIM | Edge MAE |
| --- | ---: | ---: | ---: |
| clean Lanczos | 25.4028 dB | 0.984538 | 0.017182 |
| blur 0.6 + JPEG Q35 | 23.7188 dB | 0.976996 | 0.020485 |

These historical values validate the original evaluation path; they are not the current expanded
contract hash and are not Anime4K, SESR, QuickSR or device results. The current contract records
its own hash at preparation time and adds four subtitle/profile cases.

## Android entry gates

Anime4K now has a player integration and bounded single-device function evidence, but it may not
be promoted as quality-validated or product-ready behavior until the remaining gates close:

1. Reproduce PC output on clean, blur/JPEG, line-art and subtitle/edge cases;
   save output hashes and conduct a blinded line/halo/subtitle review.
2. Freeze the exact transformed shader or ONNX graph, exporter source commit,
   commands, input/output names, layout, dtype, scale and SHA-256.
3. For SESR, compare source PyTorch, CPU ORT and candidate QNN tensors before
   any performance claim; quantify DIV2K/release-rights limitations.
4. For Anime4K, validate GLES compatibility pass by pass and prove that the
   implementation stays GPU-resident without hidden readback.
5. Measure full-frame allocated bytes and peak process memory; the AI Hub
   128x128 proxy figure must not be extrapolated to 640x360 or 1080p.
6. On the target phone, collect warmup-excluded p50/p95/p99 GPU or QNN time,
   complete effect timing, dropped/bypassed frames, 10–30 minute thermal/power
   behavior, seek/flush correctness, A/V sync and final display evidence.
7. Keep each App integration in a separate reviewed change. Anime4K is isolated
   from QuickSR/QNN/cadence changes; SESR remains an interface/export proposal
   in `ANIME_MODEL_EXPORT_PLAN.md`.
