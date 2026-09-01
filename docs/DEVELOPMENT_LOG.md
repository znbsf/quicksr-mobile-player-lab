# Development and problem log

This log keeps failed attempts and scope corrections. A closed tooling problem does not promote a model, device, correctness, performance, or human-review gate.

## 2026-08-31 — Repository isolation

- **Problem:** the source prototype directory also contained hundreds of megabytes of generated builds, vendor runtime binaries, local models, and raw device evidence.
- **Risk:** copying it wholesale would make a GitHub repository large, non-reproducible, privacy-sensitive, and legally ambiguous.
- **Action:** migrated an explicit source allowlist into a new repository and added source-only publication checks. Models and build outputs remain local and ignored.
- **Status:** source isolation implemented; publication verification still has to pass against the final Git set.

## 2026-08-31 — Initial wildcard copy did not copy Java files

- **Problem:** a literal-path PowerShell copy treated a wildcard as text, so the first pass omitted Java sources.
- **Action:** enumerated regular Java files explicitly and copied each file. The source and destination counts were checked afterward.
- **Status:** closed. No source file in the original repository was modified.

## 2026-08-31 — Standalone application identity

- **Problem:** the migrated app initially retained the old prototype application ID. Installing it would replace the earlier diagnostic app instead of coexisting with it.
- **Action:** renamed the namespace and application ID to `dev.aisystems.quicksrplayerlab` and retained relative manifest activity naming.
- **Status:** code changed; build and install verification remain pending.

## 2026-08-31 — HTP execution is not correctness

- **Observed prior result:** P3 had strict HTP placement and repeatable output, while the frozen PC golden gate failed with 28,764 mismatches out of 49,152 and maximum absolute error about 0.001516.
- **Action:** preserved that failure and created a separate P4 contract. The old gradient is calibration evidence only and cannot pass the new gate.
- **Status:** P4 thresholds are frozen, but SSIM and acceptance input hashes are pending; device-gate execution is therefore blocked.

## 2026-08-31 — Real image versus player scope

- **Problem:** a fixed 64×64 tensor probe cannot show whether the model helps a real image, while jumping directly to video would mix decode, colorspace, copies, tiling, inference, upload, timing, and backpressure failures.
- **Action:** implemented M0 as a real-image center ROI comparison: HR reference, deterministic LR generation, deterministic bilinear baseline, strict HTP output, PSNR, rendered artifacts, and receipt linkage.
- **Boundary:** M0 is not full-image processing and is not a player plugin.
- **Status:** source implemented; host build and phone interaction remain pending.

## 2026-08-31 — Player integration choice

- **Problem:** mpv user shaders can run GLSL but cannot call an ONNX Runtime QNN session. VLC CPU callbacks introduce known decode and copy penalties.
- **Action:** selected a future Media3 effect AAR plus demo player. The design exposes decoded GL textures and timestamps, then measures GL readback, tensor conversion, HTP inference, GL upload, PTS, queue depth, and dropped frames separately.
- **Status:** design only. No video frame has entered the model and no player module exists yet.

## 2026-08-31 — Publication scan findings

- **Problem:** the first whole-directory safety scan correctly found local ONNX files, a machine-specific build fallback, and inherited parent-directory references.
- **Action:** models remain ignored; the build fallback was made environment-relative. Remaining inherited documents and scripts must be sanitized, archived locally, or excluded before GitHub push.
- **Status:** open until the fail-closed publication check passes.

## 2026-08-31 — First standalone Gradle gate failed before compilation

- **Problem:** the first wrapper invocation supplied `models/...` as a relative project property, but the Android subproject resolved it below `app/` and correctly reported the locked model as missing.
- **Action:** changed property resolution to use the repository root, matching the documented standalone layout. The failed invocation is not counted as a test pass.
- **Status:** fix applied; one bounded gate rerun is required.

## 2026-08-31 — First combined Python test command failed during collection

- **Problem:** invoking both test files from the repository root did not add their hyphenated tool directories to Python's import path. Collection failed on local sibling imports before any test ran.
- **Action:** kept the failed command as an invocation problem and changed the bounded retry to run each suite from its own tool directory.
- **Follow-up:** the directory-scoped retry exposed two tests that still depended on intentionally excluded private Golden artifacts. Those tests were converted to generate rights-neutral synthetic bundles inside temporary directories.
- **Status:** closed after 9/9 derivation tests and 7/7 Golden tests passed. The original collection failure remains recorded above.

## 2026-08-31 — Publication negative-test harness leaked an expected exit code

- **Problem:** the safety assertions passed, including rejection of a synthetic ONNX and credential-shaped string, but the script left the last expected child exit code (`1`) in the caller's PowerShell state. An outer wrapper therefore reported a false harness failure.
- **Action:** reset the caller-visible exit code only after all assertions and exact temporary-directory cleanup complete.
- **Status:** fix applied; the safety findings themselves were correct and were not weakened.

## 2026-08-31 — M0 evidence commit is not yet a single transaction

- **Problem:** the four PNG files become visible as one renamed directory before the final receipt is committed. A process crash or storage failure in that interval can leave an image directory without a receipt. Stale pending-directory cleanup failures are also not yet promoted into a recovery record.
- **Current mitigation:** each run ID is unique; existing evidence is never overwritten; four images are written and synced in a private pending directory, then published together; any caught failure produces a phase-labelled FAIL receipt when storage remains available.
- **Status:** accepted for one M0 diagnostic, open for P4. P4 requires one run bundle with a final `COMMITTED` marker or an equivalent independently validated two-phase protocol.

## 2026-08-31 — First GitHub push was rejected for workflow scope

- **Problem:** GitHub created the private repository, but the HTTPS OAuth token correctly refused a commit containing `.github/workflows/source-safety.yml` because that token lacked the separate `workflow` scope.
- **Action:** did not broaden the OAuth grant and did not delete or rewrite the verified commit. A read-only SSH check showed the existing key already authenticated as the same GitHub account, so the remote was switched to SSH and the unchanged commit was pushed.
- **Status:** closed. Remote `main` matched local HEAD and the first `source-safety` run passed every step.

## 2026-09-01 — Media3 video player and neural effect became operational

- **Result:** v0.12.0 now plays local video through Media3 and can switch among original, GPU Lanczos, QuickSR CPU, and QuickSR QNN HTP paths. It remembers the last persisted video URI so the user does not need to select the same item after every launch.
- **Default profile:** the neural path preserves 16:9 and runs `640×360 → 1280×720` with QNN Sustained tuning.
- **Bound final smoke:** a `1280×720 @ 23.976023 fps` local SDR source completed 645 frames by PTS 26.860 s (`24.0134 fps`). Four stable MediaCodec windows each rendered 120 frames in about 5 seconds with zero reported drops.
- **Build:** 44 Java tests, Android lint, and debug assemble passed; the bound APK SHA-256 is `9d1a2153a844af29ddd883441c4e063217504b6e2cb649cce44aa0f64d0abf8e`.
- **Boundary:** MediaCodec render/drop is a decoder-renderer proxy, UI timings are periodic single-frame samples, and the run does not close visual quality, final display latch, A/V sync, p95/p99, or general-device claims.

## 2026-09-01 — The bottleneck was not simply “Java player versus C player”

- **Observed:** hardware MediaCodec decode and GPU Lanczos could keep up with the source. Earlier neural runs accumulated queue delay and paid heavy per-frame output/session costs.
- **Action:** added static rectangular models, persistent direct input/output tensors, ORT pinned outputs, QNN Baseline/Burst/Sustained modes, tuned graph finalization, buffer reuse, stage timings, and a lower-frequency full finite scan.
- **Result:** after the combined change, the 720p neural-output smoke sampled roughly 9 ms in `OrtSession.run`, 10 ms in output conversion, and 22 ms total for the displayed frame.
- **Lesson:** no same-build unpinned/pinned ABBA exists, so the gain cannot be attributed to pinned output alone. The next likely hotspot is output conversion/upload; replace individual hot loops with NEON/GPU/native code only after profiling, rather than replacing the whole Media3 player by assumption.
- **Details:** see [REALTIME_VIDEO_SR_LESSONS.md](REALTIME_VIDEO_SR_LESSONS.md).

## 2026-09-01 — PC-first anime matrix and real output-sized video canvas

- **Problem:** a `ByteBufferGlEffect.Processor.configure()` return value controls readback size, not the Media3 effect output texture. With a genuine 360p source, the old chain could therefore infer a 720p neural frame and then blit it back into a 360p output texture before `PlayerView` scaling.
- **Action:** added an explicit `Presentation` effect before QuickSR so the downstream output pool is already the profile output size. QuickSR still reads a static 640×360 tensor input but now writes its 1280×720 result into a 1280×720 effect canvas. Added an x86_64 emulator build that defaults to CPU and skips the unavailable HTP mode.
- **Observed emulator check:** an API 35 x86_64 AVD played a locally generated 640×360, 5-second clip and completed 75 QuickSR CPU frames. The UI reported `effect canvas 1280×720` and `640×360→1280×720`; one periodic sample showed 42 ms ORT, 233 ms queue, and 293 ms total.
- **Hot-path change:** input RGBA arrays are now pooled across frames instead of allocating one large Java byte array for every accepted frame.
- **PC baseline:** the fixed 640×360→1280×720 model ran 20 measured CPU iterations at mean 184.4 ms, p50 178.5 ms, and nearest-rank p95 209.5 ms on the current host. The deterministic synthetic line-art pair measured 33.47 dB QuickSR PSNR versus 29.55 dB bilinear.
- **Boundary:** the synthetic scores validate only the benchmark plumbing. Emulator timings do not predict QNN HTP, and this check does not close real-anime quality, final display latch, A/V sync or thermal gates.
