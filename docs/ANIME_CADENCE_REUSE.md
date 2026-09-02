# Anime cadence-aware QuickSR reuse

This is an opt-in experiment for QuickSR CPU/QNN video playback. It does not generate frames,
change timestamps, or apply the Media3 effect more than once. Every accepted input PTS still owns
one output future and one output submission. The experiment only decides whether that output uses
a new QuickSR inference or a copied, same-generation SR pixel cache.

## Safety and ownership contract

- Default and interactive mode are `OFF`; only benchmark Intent configuration can enable
  `CONTENT_AWARE_V1`.
- The existing bounded inference queue, FIFO order, output-tensor semaphore, and optional SERIAL or
  OVERLAP postprocess mode remain authoritative.
- Media3 input pixels are copied before `processImage` returns.
- A processed output is copied into a dedicated cache; the cache never aliases a `FrameResult`
  buffer owned by Media3.
- A reuse result receives a fresh pooled direct buffer, the current frame token and the current
  PTS. `FrameResult.recycle()` remains idempotent.
- Cache eligibility is bound to generation, input-stream epoch, and the most recent successfully
  inferred frame ID. If OVERLAP postprocess has not completed that exact inference, the current
  frame is processed with reason `CACHE_NOT_READY`.
- `signalEndOfCurrentInputStream()` advances the cadence-only stream epoch after the Media3
  delegate returns. This does not increment seek/flush telemetry, but it prevents a playlist or
  repeat first frame from using the preceding stream's cache.
- `flush()` advances generation and clears analyzer/cache state before Media3 can accept a new
  playback position. Old-generation work is cancelled or dropped and can never satisfy a new
  generation cache lookup.

## Signal and decision order

The analyzer samples a fixed 16x9 grid from RGBA input. Each cell records luma and local edge
strength from 4x4 deterministic sample points. It also scans every input pixel in the bottom third
into two preallocated luma buffers. The current `anime-cadence-analyzer-v1` guard treats a pixel as
a reliable bottom change when luma delta is at least 48 and edge delta is at least 32 with local
contrast at least 24; one such pixel forces a new inference. This closes sparse-grid blind spots for
high-contrast strokes above those thresholds.

This is a threshold guard, not semantic subtitle recognition. Low-contrast or sub-threshold text
can still reach `SMALL_CHANGE`; the feature therefore remains experimental and default `OFF`.
Scores are lightweight proxies:

- `sceneScore`: mean luma change plus the fraction of large block changes;
- `subtitleScore`: changed high-contrast grid cells and dense bottom-third pixels;
- `motionScore`: weighted luma and edge change.

The decision order is: generation/reference gate, hard maximum staleness, exact input CRC, scene
cut, subtitle guard, motion guard, then small-change reuse. At most two consecutive frames may be
reused, so a new inference is attempted at least every third output frame. This is content-aware;
there is no `frameIndex % 3` schedule.

`sceneScore`, `subtitleScore`, and `motionScore` are decision proxies. They are not final display
measurements, perceptual quality scores, or evidence that subtitles were recognized semantically.

## Reproducible switch

Omitting the cadence extra is equivalent to `OFF`:

```powershell
adb shell am start -a android.intent.action.VIEW `
  -d '<rights-clear-media-store-uri>' -t video/mp4 `
  -n dev.aisystems.quicksrplayerlab/.SuperResolutionActivity `
  --es dev.aisystems.quicksrplayerlab.extra.BENCHMARK_RUN_ID cadence-720-on `
  --es dev.aisystems.quicksrplayerlab.extra.VIDEO_MODE QUICKSR_QNN `
  --es dev.aisystems.quicksrplayerlab.extra.VIDEO_PROFILE FULL_720P `
  --es dev.aisystems.quicksrplayerlab.extra.VIDEO_TUNING SUSTAINED `
  --es dev.aisystems.quicksrplayerlab.extra.CADENCE_MODE CONTENT_AWARE_V1
```

Use `OFF` explicitly for the A/B control. Tensor evidence capture and cadence reuse are mutually
exclusive so a requested tensor selector cannot silently land on a reused frame.

After capturing one bounded raw Logcat file per mode under ignored `device-results/`, produce a
sanitized comparison with:

```powershell
python .\scripts\summarize_android_qnn_cadence_ab.py `
  --off-log <ignored-off-log> --off-run-id cadence-720-off `
  --off-report <ignored-off-pass-report> `
  --on-log <ignored-on-log> --on-run-id cadence-720-on `
  --on-report <ignored-on-pass-report> `
  --source-fps 15 --target-fps 24 `
  --output .\device-results\cadence-ab\summary.json
```

The existing physical-device runner also accepts `-CadenceMode OFF` or
`-CadenceMode CONTENT_AWARE_V1`; `CaptureOnly` remains restricted to `OFF`.
The summarizer rejects device error/non-PASS terminal events and requires both validator reports
to be clean PASS results bound to the exact raw-log hash, plan, case, clip, receipt, and source
manifest. Tuning, postprocess mode, model, app-source identity, profile, and ABI must also match.

## Telemetry

The configuration event records the actual `cadenceMode`, analyzer version, dense subtitle
thresholds, `cadenceMaxReuseStreak`, and owned `cadenceCacheBytes`. Every submitted frame records:

- `cadenceDecision`, `cadenceReason`, `reuseStreak`, and the exact cache-reference
  generation/input-stream epoch/frame ID;
- `cadenceAnalysisNs`, `sceneScore`, `subtitleScore`, and `motionScore`;
- cumulative `cadenceProcessedCount` and `cadenceReusedCount`;
- the existing frame/generation/PTS, CRC, queue, drop, inference, and output-submit proxy fields.

The existing FPS and output-submit timestamps remain effect-side proxies. They do not measure GPU
completion, SurfaceFlinger latch, final display cadence, or visual quality. For cadence ON, the
resolution validator reports `cadence_effect_proxy_unclassified` instead of applying its
all-frames-inferred realtime classes.

## Validation boundary

Host tests use synthetic one-on-one, one-on-two, one-on-three, slow-pan, hard-cut, subtitle-only,
flush/generation, maximum-staleness and cache-reference sequences. A device A/B must use the same
rights-clear clip and profile for OFF/ON, retain raw logs and APKs only in ignored paths, and report
inference reduction, reuse reasons, FPS/queue/drop, PSS, thermal proxies, pause/seek/reopen behavior.
No device or quality conclusion is valid until those logs and behaviors are captured and reviewed.

## Bounded physical-device result (2026-09-03)

This result used one physical Qualcomm `arm64-v8a` device, the same latest-source APK/model, the
same `FULL_720P`/`SUSTAINED`/SERIAL configuration, and the registered rights-clear
`core-bbb-720p-24-clean` clip for both 30-second runs. The validator reports were clean PASS and
were bound to the raw-log hashes and the same clip, receipt, source-manifest, model, app-source,
plan, case, and ABI identities. Two earlier ON captures each lost one Logcat sample and were
rejected rather than repaired; only the complete rerun below is accepted.

- OFF emitted a complete 676-frame sequence (661 after warmup); ON emitted a complete 680-frame
  sequence (665 after warmup). Both had queue depth at most 2 and zero runtime drops.
- ON processed 423 frames and reused 257, a 37.79% measured inference reduction. All 257 reuses
  aligned with the registered 15-to-24 fps repeat-hold map after resetting phase at each input
  stream epoch: hold recall was 100% and mapped source-motion false reuse was 0.
- ON reasons were `GENERATION_START=4`, `MOTION=393`, `SUBTITLE_GUARD=26`, and
  `SMALL_CHANGE=257`. Analyzer cost was 1.141 ms p50 and 3.180 ms p95.
- Effect output-submit proxy throughput was 23.717 fps OFF and 24.069 fps ON, a +0.352 fps proxy
  delta. This is not final display cadence, GPU completion, or a perceptual-quality measurement.
- One mid-run PSS snapshot was 357,155 KiB OFF and 371,606 KiB ON. The +14,451 KiB difference is
  an order- and GC-sensitive observation, not a steady-state memory claim. Sampled peak CPU/GPU
  thermal readings were 59.2/47.7 C OFF and 56.4/47.7 C ON; all sampled temperature statuses were
  0, but run order and accumulated heat prevent a causal thermal claim.
- Visible-controller pause held the last emitted frame ID at 1746 for three seconds; resume
  advanced it to 1823. A visible 5-second seek advanced pipeline generation from 0 to 1, recorded
  one flush/seek, dropped five old-generation in-flight frames, and made the new-generation first
  frame `PROCESS/GENERATION_START` with no cache reference. Force-stop/reopen began again at frame
  ID 1, generation 0, stream epoch 0, also with `PROCESS/GENERATION_START` and no reference.

The OFF run remained `offline` under the existing all-inference effect-side proxy, while cadence
ON is intentionally `cadence_effect_proxy_unclassified`. After repeated 720p thermal rounds, a
1080p round was not treated as feasible evidence and was not run. No final-display or broad visual
quality conclusion is claimed; low-contrast/sub-threshold text remains outside the proven guard.
