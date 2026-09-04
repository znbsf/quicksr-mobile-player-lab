# Realtime pipeline telemetry contract

Date: 2026-09-05

Status: implemented and exercised on one physical device. The telemetry distinguishes effect
throughput from SurfaceFlinger actual-present cadence; neither is photon timing, A/V-sync,
thermal, power, or visual-quality evidence.

## Scope and identities

Every processed frame carries a monotonically increasing `frameId`, a flush-isolated
`generation` plus `generationFrameId`, PTS, input CRC32 and output CRC32. CRC32 is a low-cost
per-frame stale-output identity, not a cryptographic content attestation and not a substitute for
the existing tensor/golden correctness gate. A benchmark configuration also records the model
variant and SHA-256, source identity SHA-256, build id, ABI, profile and tuning. The host runner
continues to bind the input clip SHA-256 through its redacted media registration.

Media3 `flush()` is the only public callback available here to proxy seek isolation. It advances
the generation before the delegate is flushed. Results from an older generation are counted as
dropped and are refused before GL upload; they cannot be submitted with a new PTS. A direct player
seek callback is not claimed.

## Raw nanosecond timeline

All runtime clocks use `SystemClock.elapsedRealtimeNanos()` except ORT sub-timings, which are the
existing caller-wall nanoseconds recorded by `QuickSrSession`.

| Stage | Contract |
| --- | --- |
| effect accepted | measured at `GlShaderProgram.queueInputFrame` |
| GPU readback/PBO ready | proxy: `processImage` callback after Media3 exposes the mapped buffer |
| input copy/hash | measured pixel-buffer copy plus a separately timed CRC32 identity pass |
| worker queue wait | measured from input-copy completion to worker start |
| output-tensor slot wait | measured semaphore wait for one of two bounded Java output arrays; zero in serial mode |
| output-tensor prepare | measured pool lookup or first allocation after the slot is owned |
| preprocess | measured RGBA to NCHW conversion after the output-tensor slot is owned |
| ORT/QNN | measured caller wall time; not pure NPU kernel time |
| tensor output copy | measured pinned ORT output to Java-array bulk copy; on the default deferred path this executes on the postprocess lane |
| output pack/hash | measured NCHW or NHWC to RGBA byte-array conversion plus a separately timed CRC32 identity pass |
| direct-buffer copy | measured byte-array to uploadable direct buffer copy |
| output-ready to GL-submit queue | measured wait between completed CPU output and Media3 invoking the GL callback; Media3 internal depth is not observed |
| GL upload/output submit | proxy: CPU submission around `glTexSubImage2D`, optional scale blit and callback return; not GPU completion |
| SurfaceFlinger actual present | measured separately with `dumpsys SurfaceFlinger --latency`; layer proxy, not photon timing |

The report derives raw-ns p50/p95/p99/max/mean for every observable interval and for
PTS-to-wall-clock drift. Drift is explicitly a generation-relative proxy anchored to the first
accepted frame, not input-to-photon latency.

## Queue and counters

The inference worker uses one active thread and a queue capacity of two with blocking backpressure;
it never uses an unbounded executor queue. Media3 1.11.0 pins a six-frame effect queue and one
pending PBO. Those fixed capacities are recorded, but their instantaneous internal depth is not.
The measured queue depth comes from two frame-admission permits and excludes the reserved cleanup
slot.

The default path overlaps one single-thread QNN lane with one single-thread postprocess lane. The
postprocess executor has one active task plus one queued task. Two Java output arrays and two pinned
ORT output tensors have matching bounded ownership; the pinned-output change adds 24,883,200 bytes
at 1080p. The first inference copies synchronously and performs the full finite scan. Later runs may
lease a pinned output until the postprocess lane bulk-copies it. Session close waits until both
leases return before freeing ORT tensors.

Four fixed row stripes perform the default NHWC-to-RGBA pack. Same-size 720p/1080p/1440p profiles
upload directly into Media3's output texture; the 4K display fallback retains an intermediate
1080p texture and scale blit. An optional two-PBO upload experiment is recorded but defaults off:
it lowered the CPU GL-submit proxy and consumed 16,588,800 extra bytes without improving the
two-run SurfaceFlinger pass count.

Release keeps the two frame queue slots unchanged and reserves one non-frame executor slot solely
for the worker cleanup marker. After `released=true`, no new frame task can enter; the marker is
queued behind every already-accepted frame before graceful shutdown. A bounded release timeout may
return an error while a native inference is still blocked, but the queued marker retains ownership
of eventual session close and same-worker QNN unlock. Pending frames and the final cleanup snapshot
remain attributable rather than being discarded by `shutdownNow()`.

Telemetry records `accepted`, `processed`, `late`, `dropped`, `bypassed`, current/max worker queue
depth, flush count and seek-proxy count. The frozen queue policy does not synthesize bypass or
latest-wins drop, so `bypassed` should remain zero and drops should be attributable to flush,
cancellation, rejection, release or processing failure.

## Validation and report boundary

Telemetry events use schema version 2. The host validator emits report schema version 3 and fails
closed on missing raw timestamps, non-monotonic stage clocks, malformed identities/hashes, queue
depth above capacity, missing counters, QNN strict-evidence mismatch, source-cadence mismatch, or
runtime/build configuration mismatch. Performance labels are
`effect_proxy_realtime_30_throughput`, `effect_proxy_realtime_24_throughput`, or `offline`;
even a throughput PASS is not a final-display or product-realtime conclusion. SurfaceFlinger reports
are a separate artifact and fail on cadence ratio, non-monotonic presents, or any interval above
1.5 or below 0.5 source frames.

## A/B gate

For a throughput intervention, retain a build-time control and keep source/model/input identity,
profile, tuning, frame-admission queue capacity and cadence fixed. Use alternating physical-device
runs, align the repeated clip cycle from its PTS-zero identity, and require every registered-frame
`(PTS modulo cycle duration, input CRC)` identity plus at least one clip length of matching
occurrences across the aggregate control and candidate runs. Output CRCs must remain consistent for
every shared input identity.

For final display, use `Original A1 -> QuickSR B1 -> QuickSR B2 -> Original A2` on the exact same APK.
Do not turn a 1/2 QuickSR result into a pass by adding repetitions. A new display run is justified
only after an implementation change or a trace that can associate an outlier with a stage.
CRC32 is only a stale/output-consistency signal; tensor/golden tests remain the numeric-correctness
gate. Raw logs and reports remain local and ignored until a sanitized result is intentionally
documented.
