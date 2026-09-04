# Android QNN native output-packer ABBA

Date: 2026-09-04

Status: physical-device experiment complete; candidate rejected as a default and retained only as
an explicit, default-off experiment.

## One-variable contract

The candidate replaces only the serial 1080p float-NCHW-to-RGBA output conversion. A uses the
existing Java pack into a reusable byte array followed by a copy into the pooled direct buffer. B
uses an arm64 JNI/NEON pack directly into that pooled buffer. Model, registered 180-frame video,
`FULL_1080P_3X` profile, `SUSTAINED` tuning, QNN session, queue capacity two, blocking policy,
`SERIAL` postprocess, cadence `OFF`, canvas, and APK are fixed. The order was A1, B1, B2, A2.

Normal builds keep `quickSrNativeOutputPacker=false`. Benchmark intent can explicitly select
`JAVA` or `NATIVE_NEON`; the latter fails closed unless an in-process deterministic Java/native
self-test passes. Both B configurations reported `outputPackerSelfTest=PASS` before processing.
The tested APK SHA-256 was
`1388a970e811ef548b8fba23de7af1eb6ca3e61f0c8dfd731c55cf03e0f60d5c`, with app source identity
`38812e9ae8ce3c3c3204f3bd3cb091cf1b23f30386fead284d4db6fb9781aefa` and build ID
`native-output-packer-abba`. The installed APK was pulled back and matched that hash and signing
certificate; no reinstall occurred during the final experiment.

## Implementation and correctness gates

The JNI bridge validates tensor and direct-buffer sizes, takes no persistent Java references, and
uses eight-pixel arm64 NEON conversion blocks plus a scalar tail. It preserves the Java clamp and
round behavior, including non-finite handling, and precomputes nearest-neighbor alpha mapping in a
bounded row. The Java fallback and its old constructor call sites remain intact. Frame-result
ownership, pool return on failure/stale generation, cadence-cache copies, CRC, flush, and release
all accept the direct-buffer path.

Host gates passed with 107 Java tests, 44 Python tests, `lintDebug`, `assembleDebug`, and
`assembleDebugAndroidTest`; the app APK contains the arm64-v8a JNI build. A later execution attempt
found that the first instrumentation APK registered the platform `android.app.Instrumentation`
instead of a JUnit runner, so its tests were compiled but undiscoverable. The follow-up switches to
`AndroidJUnitRunner`, JUnit4 annotations, and an APK inspection gate that requires the correct runner,
target package, test class, and all three named test methods. After the corrected test APK was
installed, the non-installing device gate found exactly three tests, observed each finish with status
code zero, and ended with `OK (3 tests)` plus instrumentation code `-1`. The device-side in-process
self-test compares randomized and boundary values,
a non-square resize, exact RGBA bytes, alpha mapping, repeated buffer ownership, and direct-buffer
requirements. It passed independently in B1 and B2. The three device instrumentation tests also
passed. This closes the tested packer correctness, boundary mapping and caller-ownership gate; it
does not add performance evidence, complete the 180-frame CRC cycle, or prove final-display quality.

The ABBA summarizer rehashes every raw log, reruns the current strict validator, checks the
chronological packer sequence and pinned configuration, and binds all runs to the same registered
media receipt. Across the observed decoder subsequences, equal input CRCs produced one output CRC:
244 aligned cross-packer occurrences had zero conflicts. They cover 70 distinct identities from the
180-frame cycle, not the complete cycle. This is partial cross-packer consistency plus the complete
deterministic startup self-test; it is not an independent golden-tensor or full-cycle proof.

## Physical results

Durations are milliseconds, p50/p95. `Total` ends at the effect output-submit proxy, not GPU
completion or final display.

| Run | Packer | Frames | FPS | Inference | Tensor copy | Output pack | Direct copy | Total | Max queue | Drop/bypass |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A1 | JAVA | 315 | 11.811 | 44.671/49.300 | 2.084/2.911 | 36.807/39.425 | 0.672/1.153 | 485.334/526.784 | 2 | 0/0 |
| B1 | NATIVE_NEON | 173 | 6.749 | 43.864/49.082 | 2.098/2.808 | 102.656/106.134 | 0.002/0.003 | 865.120/900.973 | 2 | 0/0 |
| B2 | NATIVE_NEON | 174 | 6.781 | 44.111/49.725 | 2.117/2.891 | 102.050/105.919 | 0.002/0.004 | 861.826/900.294 | 2 | 0/0 |
| A2 | JAVA | 319 | 11.899 | 44.720/49.541 | 2.077/2.993 | 36.324/39.219 | 0.649/1.112 | 485.077/591.662 | 2 | 0/0 |

Mean FPS fell from 11.855 to 6.765 (`-42.94%`). Mean run-level output-pack p50 increased from
36.566 to 102.353 ms (`+179.91%`), and total p50 increased from 485.206 to 863.473 ms (`+77.96%`).
The direct-copy p50 fell from about 0.661 to 0.002 ms, but that saving is overwhelmed inside the
measured native pack call. Inference and tensor-copy timings stayed comparable, and A2 returned to
the A1 range, so the result is localized to this candidate more strongly than to a monotonic run
order effect. The measurement does not isolate NEON arithmetic from JNI critical-array acquisition
or runtime pin/copy behavior, so it does not assign a lower-level cause. Although the JNI
specification permits nested primitive critical regions, its guidance discourages extended work in
them; the observed duration is another reason this implementation is not production-suitable.

## Lifecycle and memory boundary

A separate same-APK B lifecycle probe observed one configuration, one strict QNN session event,
one worker-cleanup completion, one release snapshot, zero error events, and no resumed App Activity
after Back. The corresponding Java probe had the same lifecycle counts. No resource leak was
observed by those counters, but the probe is bounded and does not prove long-run leak freedom.

Eight short whole-process TOTAL PSS samples ranged from 373,926 to 425,655 KiB for B and 367,113 to
387,842 KiB for A. The implementation removes one Java output staging array of 8,294,400 bytes at
1080p, but the sampled process PSS did not show a memory benefit. The probes were short and ordered,
and PSS includes runtime, codec, QNN and graphics allocations; neither range is an incremental JNI
allocation measurement.

## Decision

Reject the native packer as the default. Keep Java as the production path and retain the native
implementation only behind the explicit default-off build/benchmark controls so the negative result
remains reproducible. Do not enlarge queues or combine this with overlap to hide the regression.

The sanitized machine summary is
[android-qnn-native-output-packer-abba-summary.json](evidence/android-qnn-native-output-packer-abba-summary.json).
Its device-test section is validated from the separate sanitized
[instrumentation receipt](evidence/android-qnn-native-output-packer-device-tests.json).
Raw logs, APKs, media, device identifiers, and local paths remain ignored. Final display latch,
input-to-photon latency, visual quality, A/V sync, sustained power and thermal behavior remain
unmeasured. QNN evidence remains session-configuration evidence, not per-node placement proof.
