# Realtime pipeline telemetry contract

Date: 2026-09-03

Status: source implementation and host validation only. This document is not physical-device,
realtime, GPU-completion, SurfaceFlinger-latch, A/V-sync, thermal, power, or visual-quality
evidence.

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
| preprocess | measured RGBA to NCHW conversion |
| ORT/QNN | measured caller wall time; not pure NPU kernel time |
| output pack/hash | measured NCHW to RGBA byte-array conversion plus a separately timed CRC32 identity pass |
| direct-buffer copy | measured byte-array to uploadable direct buffer copy |
| GL upload/output submit | proxy: CPU submission around `glTexSubImage2D`, blit and callback return; not GPU completion |
| SurfaceFlinger latch/final display | unmeasured; no zero-valued placeholder is emitted |

The report derives raw-ns p50/p95/p99/max/mean for every observable interval and for
PTS-to-wall-clock drift. Drift is explicitly a generation-relative proxy anchored to the first
accepted frame, not input-to-photon latency.

## Queue and counters

The worker uses one active thread and a queue capacity of two with blocking backpressure. It no
longer uses an unbounded executor queue. Media3 1.11.0 itself pins a six-frame effect queue and one
pending PBO; those capacities are recorded, while their internal instantaneous depth remains
unmeasured. The measured queue depth is the frame-admission depth derived from the two frame-slot
permits; it deliberately excludes the reserved cleanup slot.

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
depth above capacity, missing counters, QNN strict-evidence mismatch, or any attempt to label final
display as measured. Performance labels are now `effect_proxy_realtime_30`,
`effect_proxy_realtime_24`, or `offline`; even a proxy-realtime result is not a final-display or
product-realtime conclusion.

## A/B gate

No throughput intervention is included in this change. This worktree has no local model payload,
and this task did not authorize a physical-phone run, so neither bounded inference/postprocess
overlap nor a JNI/NEON packer can be safely attributed here.

The next A/B must keep APK/source/model/input identity, profile, tuning, queue capacity and queue
policy fixed; change exactly one of:

1. bounded inference/postprocess overlap with explicit buffer ownership; or
2. Java packer versus JNI/NEON direct output packer.

Run an alternating/ABBA workload, require matching per-frame identities and correctness hashes,
and compare raw-ns p50/p95/p99/max plus counters and memory. Do not merge an optimization on code
completion or host microbenchmarks alone; require an authorized physical-device run with the same
workload and source/model identities.
