# Android QNN postprocess-overlap A/B

Date: 2026-09-03

Status: physical-device experiment complete; candidate retained behind a default-off build flag.

## One-variable contract

The candidate changes only scheduling: one QNN inference thread may overlap one bounded
NCHW-to-RGBA pack/copy thread. Model, registered video, profile, `SUSTAINED` tuning, input cadence,
frame-admission queue capacity two, and blocking policy stay fixed. A uses
`-PquickSrPostprocessOverlap=false`; B uses `true`. Both measured APKs reported app source identity
`f7c2f8a5f1bf6ba445d1750884064019c08b8ea79ea5d5632ddf06056fa01134`.

The final valid order was A8, B8, A9, B11. A separate B9 and B10 were rejected because Logcat lost
frame events while application counters reported no processing drop. They are not included. The
summarizer rehashes each raw log, checks its report/plan/case/run identity, reruns the current strict
validator, and requires the SERIAL/OVERLAP/SERIAL/OVERLAP order.

## Physical results

All durations are milliseconds and shown as p50/p95. `total` ends at the effect output-submit
proxy, not GPU completion or final display. `drift` is the generation-relative PTS/wall proxy.

| Run | Output | Mode | Frames | FPS | Queue wait | Tensor-slot wait | Inference | QNN output copy | Output pack | Direct copy | Total | Drift | Max queue | Drop/bypass | Extra tensor |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A8 | 720p | SERIAL | 656 | 23.511 | 0.22/93.12 | 0/0 | 11.60/16.77 | 1.00/1.65 | 20.16/25.14 | 0.28/0.81 | 210.04/231.73 | 1226.92/1264.45 | 2 | 0/0 | 0 |
| B8 | 720p | OVERLAP | 666 | 23.778 | 0.20/41.53 | 0.01/4.63 | 12.53/18.62 | 1.14/1.82 | 23.36/29.13 | 0.27/1.06 | 210.27/222.95 | 1311.29/1323.52 | 2 | 0/0 | 10.55 MiB |
| A9 | 720p | SERIAL | 650 | 23.635 | 3.31/106.66 | 0/0 | 11.80/16.00 | 1.04/1.65 | 23.56/28.02 | 0.26/1.03 | 210.14/267.86 | 1589.35/1661.76 | 2 | 0/0 | 0 |
| B11 | 720p | OVERLAP | 651 | 23.644 | 0.20/47.48 | 0.01/6.87 | 12.60/15.76 | 1.16/1.78 | 24.79/30.47 | 0.26/0.82 | 210.16/227.59 | 1625.22/1638.21 | 2 | 0/0 | 10.55 MiB |
| A8 | 1080p | SERIAL | 312 | 11.710 | 240.02/259.37 | 0/0 | 46.32/51.45 | 2.09/2.86 | 35.73/39.73 | 0.64/1.53 | 493.27/534.60 | 2270.95/2599.04 | 2 | 0/0 | 0 |
| B8 | 1080p | OVERLAP | 487 | 18.283 | 28.02/131.88 | 0.01/0.01 | 40.90/49.94 | 2.29/3.32 | 36.43/40.72 | 0.93/1.84 | 236.69/607.64 | 2351.82/2697.96 | 2 | 0/0 | 23.73 MiB |
| A9 | 1080p | SERIAL | 290 | 11.160 | 246.28/263.16 | 0/0 | 45.66/50.61 | 1.99/2.78 | 37.01/40.99 | 0.80/1.31 | 507.32/538.27 | 2957.48/3267.16 | 2 | 0/0 | 0 |
| B11 | 1080p | OVERLAP | 464 | 17.638 | 112.48/132.51 | 1.02/4.33 | 40.98/49.52 | 2.36/3.31 | 41.92/46.02 | 0.58/1.31 | 257.55/643.10 | 2748.14/3006.18 | 2 | 0/0 | 23.73 MiB |

Mean observed FPS changed from 23.573 to 23.711 at 720p (+0.6%, effectively source-cadence
limited), and from 11.435 to 17.960 at 1080p (+57.1%). At 1080p the mean run-level total p50 fell
from 500.30 to 247.12 ms, while total p95 rose from 536.44 to 625.37 ms. Both profiles remain in
the report's `offline` class.

## Identity, lifecycle, and memory boundary

The registered clip is 180 frames with an inferred repeated-cycle duration of 7,500,000 us. Both
modes covered all 180 aligned `(PTS modulo cycle, input CRC)` identities. The aggregate matched
occurrence counts were 1,336 at 720p and 569 at 1080p; equal input CRCs had zero output-CRC
conflicts. This is a stale/output-consistency result, not numerical correctness: the pack algorithm
is unchanged, and tensor/golden tests remain the correctness gate. Individual overloaded 1080p
runs received different decoded-frame subsequences from Media3.

On the final lifecycle-only safety revision, pause settled at frame 221 and stayed at 221, resume
reached 341, and seek advanced flush/generation twice. The processed sequence had no old-generation
output after the new PTS, no error, and no bypass. Three independent reopen/Back cycles each
reported one QNN strict event, one worker cleanup, one release, and zero errors. A 1080p sustained
probe observed process TOTAL PSS from 304,999 to a sampled peak of 483,853 KiB; it is a whole-process
measurement, not incremental overlap memory. Battery temperature was 40.5 C before and after, which
is only a coarse proxy, not thermal stability evidence.

## Decision and next single variable

The throughput gain is real, but the 1080p p95 tail regression and additional 23.73 MiB tensor make
the candidate unsuitable as the default. The code and telemetry remain reproducible with
`-PquickSrPostprocessOverlap=true`; normal builds stay serial.

The next single-variable experiment should be a JNI/NEON direct output packer against the serial
baseline. The 1080p Java output pack remains about 36-42 ms at p50 and 40-46 ms at p95. No JNI/NDK
app layer exists yet, so that experiment must isolate the new native build/ABI path and keep the
model, QNN execution, queue policy, input, tuning, and cadence unchanged.

QNN evidence remains session-configuration evidence, not per-node provider-placement proof. Final
display latch, input-to-photon latency, visual quality, A/V sync, power, and thermal stability remain
unmeasured.
