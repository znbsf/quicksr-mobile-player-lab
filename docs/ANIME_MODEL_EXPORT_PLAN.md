# Anime model source, export and Android interface plan

Status date: 2026-09-03

The Anime4K Small portion is implemented in v0.15.0 as a separate Media3 GL
effect; the SESR portion remains a proposal. The work does not modify
`QuickSrVideoEffect`, QNN setup, queue policy or cadence behavior.

## Source-only artifact preparation

Only the three license-cleared artifacts in
`pc-benchmark/anime-model-candidates.json` are accepted by the fetcher. Files
are written under ignored `build/anime-candidate-cache/`; existing mismatched
files cause failure instead of overwrite.

```powershell
python .\pc-benchmark\validate_anime_model_candidates.py
python .\pc-benchmark\fetch_anime_candidate_artifact.py anime4k-v4.0.1-upscale-cnn-x2-s
python .\pc-benchmark\fetch_anime_candidate_artifact.py anime4k-v4.0.1-upscale-cnn-x2-m
python .\pc-benchmark\fetch_anime_candidate_artifact.py sesr-m5-2x-float32-checkpoint
```

Do not add AnimeVideo-v3, Real-CUGAN, FSRCNNX LineArt, AnimeSR or VQD-SR
coordinates until their independent artifact license, byte count, SHA-256 and
training/evaluation data boundary are recorded.

## Common offline output contract

Prepare the source-original synthetic line/subtitle fixture:

```powershell
python .\pc-benchmark\anime_candidate_benchmark.py prepare `
  --output .\build\pc-benchmark\anime-candidate-contract
```

After running `fetch_open_assets.py`, add the existing CC BY comic fixtures:

```powershell
python .\pc-benchmark\anime_candidate_benchmark.py prepare `
  --include-open-assets `
  --output .\build\pc-benchmark\anime-candidate-contract-open
```

A candidate adapter reads `contract.json` and writes an RGB PNG named
`<case-id>.png` for every input. Evaluate without trusting candidate metadata:

```powershell
python .\pc-benchmark\anime_candidate_benchmark.py evaluate `
  --contract .\build\pc-benchmark\anime-candidate-contract\contract.json `
  --outputs .\build\pc-benchmark\anime4k-small-outputs `
  --candidate-id anime4k-upscale-cnn-x2-s `
  --output .\build\pc-benchmark\anime4k-small-report.json
```

The evaluator measures output quality only. A candidate-specific runner must
separately record process identity, model/shader SHA-256, warmups, per-frame
samples, p50/p95/p99, allocated bytes and peak process/GPU memory.

## Anime4K Small port implementation and device plan

1. Start from the exact v4.0.1 Small shader bytes in the artifact manifest.
2. Translate mpv `//!HOOK`, `//!BIND`, `//!SAVE` and component metadata into
   explicit GLES textures/passes. Preserve operation order, RGB range,
   sampling, edge addressing and the residual-over-bilinear output.
3. Build a host shader runner or libplacebo fixture first and freeze output
   hashes for every common-contract case.
4. Implement a standalone Android GPU experiment only after host equivalence.
   Its proposed boundary is texture in, texture out, width/height/PTS/generation
   metadata, and a completion fence. It must not expose CPU RGBA buffers.
5. Compare Small with Medium only after Small meets the device budget. The
   production candidate remains Small unless Medium shows a human-reviewed
   quality gain within the same thermal and frame budget.

Compilation success is insufficient. Pass-by-pass hashes or tolerant pixel
comparisons, GPU timer queries, allocation accounting and final displayed-frame
inspection are required.

## SESR-M5 2x export proposal

1. Use Qualcomm AI Hub Models commit
   `b703d5bed55658f85d9259d596a984e81fb4a986`, model asset version 3, and the
   hash-pinned 2x checkpoint from AIMET Model Zoo release-tag commit
   `59640d130992f984fe71339c27221aa6e3434aef`. Verify the archive before any
   PyTorch load.
2. In a disposable ignored environment, inspect the pinned CLI help and record
   the exact supported export invocation. Do not guess flags or depend on a
   floating package version.
3. Export an initial dynamic NCHW float32 RGB ONNX and a fixed
   `1x3x360x640 -> 1x3x720x1280` graph for parity with the current project
   profile. Keep both local and record exporter/package versions plus hashes.
4. Compare deterministic source-model and CPU ORT outputs. Require finite
   tensors, exact names/shapes, and a documented tolerance before conversion.
5. Submit or compile the validated graph for the chosen Qualcomm target and
   QNN/ORT route. W8A8 is a separate variant with its own calibration-source,
   quantization and accuracy record; it is not implied by the float export.
6. Verify QNN node placement/optrace, context identity, fixed-shape I/O,
   alternating-frame hashes and full-frame memory. A 128x128 AI Hub proxy job
   is useful provenance but not project-device evidence.

The proposed future App-facing contract is a new immutable model descriptor:

```text
id + artifactSha256 + scale
input(name, NCHW/NHWC, dtype, RGB range, fixed shape)
output(name, layout, dtype, fixed shape)
backend requirements + expected provider + context identity
```

Any mismatch must fail before playback. A later integration change may map
that descriptor into the existing runtime, but this model-lab worktree does not
change the effect or runtime API.

## Publication and rights boundary

- Never commit unreviewed shaders, checkpoints, ONNX, TFLite, QNN contexts,
  APKs, media, raw outputs, traces or device receipts. The exact MIT-licensed
  Anime4K x2 Small source is the sole reviewed shader exception: its notice,
  commit, byte count and SHA-256 are retained and publication-gated.
- The artifact manifest is a reproducibility coordinate, not a grant of rights.
- Preserve Anime4K MIT and SESR/AIMET BSD notices in any separately reviewed
  distribution.
- DIV2K is academic-research-only. Public model/APK distribution remains a
  human legal gate even though the selected checkpoint is marked BSD-3-Clause.
- All transformed outputs and device evidence stay in ignored local storage
  until aggregated and manually redacted under `PUBLICATION_BOUNDARY.md`.
