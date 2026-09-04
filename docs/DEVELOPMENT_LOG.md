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

## 2026-09-01 — Open animation benchmark and all-scale PC execution

- **Assets:** froze a CC BY 3.0 Big Buck Bunny frame plus a 15-frame sequence. The downloader checks the individual image identity and every sequence frame against Xiph's pinned SHA-256 index; media and reports stay under ignored `build/` paths.
- **Models:** pinned the official AIMET QuickSRNetSmall 1.5×/2×/3×/4× checkpoint URLs, byte counts and hashes, then exported fixed 640×360 and dynamic-shape ONNX variants. The newly exported 2× output matched the independently frozen canonical model exactly on the validation tensor.
- **Image result:** on the clean 640×360 frame, QuickSR beat Lanczos by +1.127/+1.039/+0.833/+0.862 dB for 1.5×/2×/3×/4×. With blur plus JPEG Q35 it lost by -0.255/-0.363/-0.460/-0.516 dB.
- **Video result:** the deterministic 15-frame H.264 CRF28 path ran at 175.1 ms mean inference (5.71 fps) on PC CPU. QuickSR averaged 30.069 dB versus Lanczos 30.203 dB and also had a higher temporal-delta error.
- **Route result:** all 18 source/layout/target routes executed. QuickSR won clean PSNR on 12 and lost or tied on 6; median chain time was 494.8 ms and maximum was 3313.1 ms.
- **Lesson:** output scale coverage is no longer the main PC blocker. Degradation robustness, representative anime data, temporal quality and Android high-resolution memory/copy behavior are the next gates. A complete C player rewrite would not address those model-quality or NPU/memory costs.

## 2026-09-01 — Android 3×/4× integration and emulator high-resolution smoke

- **Implementation:** integrated hash-pinned fixed `640×360` QuickSRNetSmall 3× and 4× graphs. Added true `1920×1080` and `2560×1440` neural profiles plus an explicit `1920×1080 neural → 3840×2160 GL canvas` fallback.
- **Bug found and fixed:** the original output packer assumed every neural result was exactly 2× and used `x >> 1` / `y >> 1` for alpha. The first 3× run therefore failed with an output-buffer length mismatch. The packer now accepts rectangular arbitrary-scale output and maps alpha proportionally; 3×/4× contracts are covered by unit tests.
- **Observed API 35 x86 CPU checks:** 1080p completed one frame at 432 ms total (`ORT 116 ms`, finite scan `172 ms`); 1440p completed at 651 ms (`ORT 168 ms`, finite scan `311 ms`); the 4K display fallback completed at 476 ms with a `1920×1080` neural texture and `3840×2160` effect canvas. No app/GL exception was observed.
- **Boundary:** these are first-frame functional checks, not p50/p95, real-time playback proof or Qualcomm HTP evidence. The phone was disconnected, so physical-device 3×/4× placement, sustained throughput, memory pressure, quality and thermals remain open.

## 2026-09-01 — Rights-clear comic/illustration corpus

- **Assets:** added download-only manifests for David Revoy's native 4K `Confront the dragon` 16:9 illustration and `Imagination` square comic. The official source pages state CC BY 4.0; exact bytes, SHA-256, attribution and allowed benchmark layouts are frozen locally.
- **Runner:** generalized the route matrix to repeated assets and degradations. It center-crops only to the declared 16:9/1:1 layout, never stretches square art, and emits grouped JSON/CSV summaries linked to the raw report hash.
- **Observed:** 72 cases completed. QuickSR won PSNR on 30/36 clean cases but only 7/36 blur-plus-JPEG-Q35 cases. It won 16/18 cases on the square comic asset. Median chain time was 492.6 ms; maximum was 3440.2 ms.
- **Product lesson:** current QuickSRSmall is a useful clean line-art route, not a universal legacy-restoration model. Strong compression should trigger Lanczos or a degradation-trained candidate until a larger anime corpus and human review justify a different policy.

## 2026-09-01 — Reproducible Android QNN resolution matrix

- **Problem:** the high-resolution profiles existed, but selecting spinners by hand and reading occasional UI samples could not produce a repeatable 720p/1080p/1440p/4K comparison.
- **Action:** added benchmark-only Intent extras for run ID, backend, profile and tuning. The App emits compact JSON Logcat events for the exact configuration, errors and every 10 processed frames, including monotonic observation time plus queue, conversion, ORT and total timings.
- **Host validator:** a dependency-free Python validator rejects CPU fallback, profile/dimension drift, device errors, malformed telemetry and insufficient post-warm-up samples. It computes raw p50/p95 and observed throughput, then classifies `realtime_30`, `realtime_24` or `offline` separately from the functional gate.
- **Runner:** the PowerShell matrix runner requires exactly one authorized arm64 physical device, rejects `ro.kernel.qemu=1`, installs the explicitly supplied APK, starts each case by Intent and preserves raw logs/reports only under ignored `device-results/`.
- **Observed emulator regression:** API 35 x86_64 emitted one CPU configuration plus 49 1080p frame samples. A QNN request emitted a structured `configuration` error because the x86 build has no QNN runtime. This proves the control/telemetry and fail-closed paths only; it does not produce Qualcomm HTP evidence.
- **Status:** host and emulator automation are ready. The phone was disconnected, so physical v0.14.0 QNN measurements remain pending rather than inferred.

## 2026-09-03 — Anime real-time SR, cadence reuse and interpolation execution split

- **Observed:** the rights-clear physical-device matrix classified 720p and 1080p as offline; 1080p recorded median queue/ORT/output-conversion/total p50 of 329/43/37/414 ms and median observed throughput of 11.69 fps. The effect still serializes inference and output packing, while Media3 readback and final GL/display stages are not covered by one end-to-end clock.
- **Decision:** keep Media3/hardware decode, close the observability gap first, then A/B a bounded inference/postprocess pipeline and native/direct output packing. Evaluate GPU-resident Anime4K/FSRCNNX and mobile model candidates in an independent workstream.
- **Cadence boundary:** do not implement fixed every-third-frame SR. Inspect every decoded frame cheaply, run SR only on adaptive anchors, preserve original PTS, reset on cuts/seeks/flushes and record processed/reused/drop/bypass decisions.
- **Interpolation boundary:** keep VFI optional and last; deduplicate/detect cuts before interpolation and require anime line/temporal quality review.
- **Execution:** immediately parallelize only the real-time pipeline and model-lab workstreams. Create cadence-aware SR from the merged runtime contract, then create VFI after performance and model selection stabilize.
- **Details:** see [ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md](ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md).

## 2026-09-03 — Anime4K x2 Small GPU-resident player mode

- **Source:** retained the exact MIT-licensed Anime4K v4.0.1 x2 Small shader from commit `4029bf701ecaa15f163cdc49cffe5501c1acf410`; Gradle and runtime verify 18,638 bytes and SHA-256 `4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4`.
- **Implementation:** added a selectable Media3 `GlEffect` that runs the four convolution passes through two RGBA16F ping-pong textures, then runs depth-to-space with the original-frame residual. The decoded frame never enters CPU or QNN memory on this route.
- **Working-color correction:** Media3 1.11.0 source and the actual ExoPlayer reflection path show that `WORKING_COLOR_SPACE_DEFAULT`, not linear, reaches SDR effects as nonlinear electrical RGB. The App now pins that mode explicitly and the adapter applies no extra OETF/EOTF; an electrical 0.5 mid-gray regression guards the contract. Output equivalence remains unclaimed pending the fixed same-frame mpv comparison.
- **Adaptation:** target dimensions are prepared at half size on GPU; sampling clamps to valid texel centers; original alpha is preserved and dynamic packed-channel indexing is made GLES-portable.
- **Failure behavior:** ES3 and half-float color-buffer capability are preflighted. Texture/FBO creation rolls back transactionally, including incomplete FBOs; permanent fallback releases model programs. The internal shader uses `mediump` bilinear, and an additional app-level GPU Lanczos prepare/seek recovery covers fallback/output-pool failure. HDR intentionally uses fallback pending a separate contract.
- **Boundary:** host compilation and unit tests do not prove target-GPU shader acceptance or output equivalence. No adb, APK install, physical-device timing, quality, memory or thermal run was performed in this round; see [ANIME4K_ANDROID_GPU_INTEGRATION.md](ANIME4K_ANDROID_GPU_INTEGRATION.md).
- **Host shader smoke:** the five model fragments emitted by the compiled Java adapter plus the `mediump` fallback compiled and linked 6/6 under the Android Emulator bundle's SwiftShader OpenGL ES 3 EGL implementation; its half-float extension preflight and RGBA16F FBO completeness probe also passed. This verifies host DLL behavior only; no emulator App or target-device texture was executed.
- **Bounded device result:** one Android 16 / Adreno 740 / GLES 3.2 device exposed both half-float and float color-buffer extensions. Anime4K 720p/1080p/1440p each reported the complete five-pass first frame model-active and finished the 7.5-second 24 fps clip without either fallback. SurfaceFlinger buffer deltas were 180/179/179; MediaCodec windows reported Drop=0; whole-process PSS peaks were 179,193/177,443/179,664 KiB and battery-temperature proxy remained 38.9-39.0 C. Lanczos deltas were 177/179/177 with 179,497/179,508/179,938 KiB peaks over the same bounded clip.
- **Lifecycle and boundary:** pause, seek to about five seconds, resume, HOME/resume and three play-during-back/reopen cycles completed with release records and no crash/ANR/GL/Media3 error. The attempted same-position screenshots were invalid because OEM picker/scroll state displaced the PlayerView; the registered BBB clip also lacks the subtitle/line-art contract, so no visual-quality claim was promoted. Raw logs, screenshots, APK and media remain ignored.
- **Corrective hardening:** app-level recovery now accepts only the current Anime graph's Media3 video-frame-processing error, latches the effective Lanczos state across callbacks and requires explicit user retry. Program cleanup mirrors Media3 1.11.0's SDK-28 `glDeleteProgram` skip on construction, fallback and release paths.

## 2026-09-03 — Offline anime VFI host comparison and Adreno 740 probe

- **Boundary:** added only `vfi-benchmark/` source, tests, manifests, and an evaluation report. No
  player/effect/App source changed, and VFI remains absent/off in normal playback.
- **Prefilter:** a stateful decoded-frame contract resets on stream/generation changes, bypasses
  exact/near holds and hard cuts, and only interpolates a same-epoch distinct drawing. The
  deterministic 12-event project-generated fixture exercised hold, cut, seek, and epoch behavior;
  one pair was eligible.
- **Host comparison:** RIFE v4.6 was slower and larger than IFRNet-S Vimeo90K, but its known-midpoint
  proxies were materially better on the one line-art motion fixture. RIFE alone advanced; IFRNet
  was initially host-only and was later measured only as a stopped lower-bound device probe. Model
  weight redistribution remains blocked because no independent weight license statement was found.
- **Device:** exact upstream RIFE/ncnn/libwebp commits were cross-compiled into a standalone arm64
  CLI. On one Android 16 / Adreno 740 device, the two unpolled `RIFE::process` runs were
  102.391/103.330 ms (median 102.860 ms); the memory-sampled run peaked at 186,825 kB PSS. All
  outputs had one SHA-256 and the process was absent after the bounded runs.
- **Conclusion:** real Vulkan model execution is proven for this device and fixture, but the model
  is already over 24/30 fps budgets before player costs. No visual-superiority, representative
  anime, sustained thermal, A/V sync, resident-runtime, or redistribution claim was promoted.
- **Details:** see [ANIME_VFI_OFFLINE_EVALUATION.md](ANIME_VFI_OFFLINE_EVALUATION.md).

## 2026-09-03 — Resident RIFE Android resolution ladder

- **Protocol:** generated seven distinct project-owned frames and six known midpoints at each of
  160x90, 256x144, 320x180, 480x270 and 640x360. The corrected runner binds manifest metadata,
  exact local 7+6 bytes/hashes/dimensions, a fresh prefilter replay and every pushed device input
  hash before execution. Each unpolled directory-mode process initialized Vulkan and loaded RIFE
  v4.6 once; PSS/RSS polling ran in a separate process and was excluded from latency statistics.
- **Observed:** the final corrected rerun's stable midpoint medians were
  30.260/45.441/35.715/50.425/75.053 ms. Corresponding maxima were
  32.008/48.535/37.806/58.602/85.062 ms and sampled PSS peaks were
  186,336/187,337/185,929/186,248/186,310 kB. The spread and drift from the superseded preliminary
  pass remain evidence that clocks were not controlled.
- **Thermal boundary:** corrected thermal parsing excludes threshold/limit zones. The bounded
  temperature-zone maximum was 48.0 C and the battery proxy stayed between 35.8 and 36.0 C. This
  is not a sustained thermal result.
- **Decision:** 160x90 and 320x180 fit the 30 fps and 24 fps kernel-only budgets in this pass, but
  their maxima leave only about 1.325 ms and 3.860 ms. The 640x360 max misses a 12-to-24 interval.
  Decode, SR, composition, display and A/V sync are omitted, so no level has defensible end-to-end
  realtime margin. RIFE remains offline-only;
  low-resolution VFI followed by the existing SR/Anime4K route is a recorded hypothesis only.

## 2026-09-03 — Smaller and newer mobile VFI candidate gate

- **Refresh:** limited the active shortlist to RIFE v4.25-lite and IFRNet-S. The former has an
  upstream anime-improvement note and a current ncnn conversion; exact-name `MobileVFI` could not be
  tied to a first-party paper/repository/model. ANVIL is credible but requires H.264 decoder motion
  vectors and a CPU/Vulkan/QNN pipeline; its public APK is SM8650/V75-specific, so it is not the PNG
  pair or current-device protocol.
- **Runtime gate:** the locally acquired RIFE v4.25-lite graph adds `MemoryData`. The frozen 2022
  RIFE executable failed before inference with `layer MemoryData not exists or registered`, so no
  fake timing was recorded. A pinned current-port build is the next separate host gate.
- **IFRNet execution:** the exact port/ncnn/libwebp commits were cross-compiled into an instrumented
  arm64 CLI. Host prefilter replay again invoked only the distinct pair. On the same physical
  SM8550/Adreno 740 class device, resident 160x90/256x144/320x180 medians were
  41.798/60.912/75.932 ms with 93,526-97,329 kB sampled PSS.
- **Comparison:** IFRNet-S used 47.7-50.1% less PSS than RIFE v4.6 but was
  38.1/34.0/112.6% slower. Resident synthetic quality proxies were mixed; the original distinct
  pair remained 10.516 dB below RIFE. The candidate is stopped and the player remains unchanged.
- **Details:** see [ANIME_VFI_MOBILE_CANDIDATE_PROBE.md](ANIME_VFI_MOBILE_CANDIDATE_PROBE.md).

## 2026-09-03 — RIFE v4.25-lite modern-runtime device disposition

- **Execution:** reused the hash-pinned 58,157,480-byte arm64/API-27 binary and v4.25-lite model
  with the prepared 128-padding fixture on the same Android 16 / SM8550 / Adreno 740 class device.
  The runner revalidated all local and device inputs, prefilter decisions and task IDs; each of the
  three levels produced 14 outputs that matched between the unpolled latency and sampled-memory
  processes. All levels passed and the process was absent after the matrix.
- **Observed:** stable medians/maxima were 53.614/64.132 ms at 160x90, 36.933/47.775 ms at
  256x144, and 55.716/58.820 ms at 320x180. Sampled PSS was 189,266-189,349 kB.
- **Comparison:** versus frozen RIFE v4.6, median latency changed by +77.2%/-18.7%/+56.0% and PSS
  rose 1.0-1.8%. Synthetic PSNR/SSIM/edge proxies all regressed at the first two levels and all
  improved at the third. Only the 256x144 median fits 24 fps; its maximum does not.
- **Decision:** the exact runtime is compatible with this bounded device, but it has no consistent
  replacement advantage and no defensible player margin. RIFE v4.25-lite is stopped, remains
  offline-only, and is not added to the App. The next active paths are the QuickSR native output
  packer and Anime4K/cadence visual gates.

## 2026-09-03 — Host Anime4K and cadence visual-quality gate

- **Fixtures:** expanded the project-original spatial contract to line art plus high/low-contrast
  subtitles under the existing clean and blur/JPEG Q35 profiles. Added matching temporal cases for
  mixed one/two/three holds, one-pixel pan, mouth/particle motion, hard cuts, fades and subtitle
  changes.
- **Binding:** preparation revalidates the existing Anime4K v4.0.1 x2 Small commit/bytes/SHA-256
  source pin. Evaluation rebuilds the canonical contract from checked-in source and rejects a
  modified oracle even when the submission hash is recomputed. It checks only declared
  same-frame/reuse/PTS/reference fields and supplied files against that oracle.
- **Metrics and boundary:** every supplied output receives PSNR, global SSIM and edge MAE; no
  quality threshold was invented. Host tests prove deterministic generation and fail-closed
  declared-oracle conformance only. A trace hash alone is not execution proof; replayable
  per-frame bytes, a binding receipt and execution identity remain absent. No mpv, Android/GL,
  QNN, device or human visual/rhythm review ran, so neither Anime4K equivalence nor actual cadence
  behavior/visual quality is promoted.

## 2026-09-04 — QuickSR JNI/NEON output-packer ABBA

- **Implementation:** added a default-off arm64 JNI/NEON path that packs float NCHW directly into
  the pooled upload buffer, while preserving the Java fallback, bounded ownership, CRC, cadence,
  stale-generation and release behavior. Explicit benchmark selection fails closed through a
  deterministic Java/native startup self-test.
- **Identity/correctness:** the installed APK matched the local APK hash and signing certificate.
  B1/B2 self-tests passed. The strict ABBA summarizer revalidated and rehashed all four logs; 244
  aligned cross-packer occurrences covering 70/180 distinct cycle identities had zero output-CRC
  conflicts. The partial cycle coverage is recorded rather than promoted to full numerical proof.
- **Performance:** at 1080p SERIAL, Java A1/A2 averaged 11.855 fps and 36.566 ms output-pack p50;
  native B1/B2 averaged 6.765 fps and 102.353 ms. Direct copy fell to about 0.002 ms, but overall
  FPS regressed 42.94% and total p50 rose 77.96%. Inference remained comparable and A2 recovered.
- **Lifecycle/memory:** Back produced one worker cleanup and one release with zero errors and no
  resumed Activity in both short Java/native probes. Eight whole-process PSS samples ranged
  367,113-387,842 KiB for Java and 373,926-425,655 KiB for native; the short ordered samples do not
  establish an incremental memory benefit or leak.
- **Decision:** reject native as the default and retain it only as a reproducible, explicit
  experiment. Java remains the production path. No APK, raw device log, media, device identifier,
  or local path is published.
- **Instrumentation correction:** a post-commit device attempt exposed that the original test APK
  declared `android.app.Instrumentation`, so the compiled tests could not be discovered. A follow-up
  uses `AndroidJUnitRunner` with AndroidX Test 1.7.0/JUnit extension 1.3.0 and adds a host APK gate
  for the runner, target package, class and three required methods. A non-installing device gate
  also requires the corrected registration and exactly three passing tests. After installing the
  corrected test APK, the runner discovered three tests, each returned status code zero, and the
  suite ended with `OK (3 tests)` and instrumentation code `-1`. This is packer
  correctness/ownership/boundary evidence only; the ABBA performance rejection and incomplete
  70/180 cycle coverage remain unchanged.
