# Anime host visual-quality gates

Status date: 2026-09-03

This host-only gate prepares deterministic project-original images and sequences for the existing
Anime4K x2 Small and QuickSR cadence workstreams. Generated PNGs, submissions and reports stay
under Git-ignored `build/`; the repository commits only generators, evaluators, tests and this
protocol. It does not call `adb`, execute a physical device or alter the QuickSR hot path.

## Frozen inputs and fixture coverage

`pc-benchmark/anime_visual_quality_gate.py prepare` first re-reads
`pc-benchmark/anime-model-candidates.json` and fails unless the vendored Anime4K v4.0.1 x2 Small
source still matches its frozen commit, 18,638-byte length, SHA-256 and MIT source policy. It then
reuses the existing `anime_candidate_benchmark.py` generator and
`degradation-profiles.json` rather than defining a second spatial metric or degradation model.

The spatial contract has six cases:

- source-original thin/thick line art, high-contrast pixel subtitles and deliberately
  low-contrast pixel subtitles;
- each source in `clean-lanczos` and `legacy-soft-jpeg-q35` form;
- the existing 2x RGB PNG protocol, Lanczos baseline and PSNR/global-SSIM/edge-MAE definitions.

The temporal contract has 14 cases: each of seven sequences is emitted under the same clean and
blur/JPEG profiles. The sequences cover mixed one/two/three-frame holds, one-pixel slow pan,
localized mouth and particle motion, a hard cut with holds on both sides, a six-step fade, and
high- and low-contrast subtitle changes. Every frame freezes its input/reference SHA-256, integer
24 fps PTS, expected `PROCESS`/`REUSE` decision and exact processed-frame reference identity.

Prepare the ignored contract with:

```powershell
python .\pc-benchmark\anime_visual_quality_gate.py prepare `
  --output .\build\pc-benchmark\anime-visual-quality-gate
```

The command also writes `submission-template.json`. Its decision, PTS, reference and input fields
are submitter declarations. Copying the oracle into this template can pass declared conformance,
but it is not evidence that an adapter or analyzer ran. RGB PNG files remain directly hash-checked
artifacts; their association with runtime frames is still declared until trace evidence is bound.

## Fail-closed declared-oracle conformance

```powershell
python .\pc-benchmark\anime_visual_quality_gate.py evaluate `
  --contract .\build\pc-benchmark\anime-visual-quality-gate\contract.json `
  --submission .\build\pc-benchmark\anime-visual-quality-gate\declared\submission.json `
  --output .\build\pc-benchmark\anime-visual-quality-gate\declared\report.json
```

Evaluation first rebuilds a canonical contract in a temporary directory from the version-controlled
generator, degradation profiles and current Anime4K pin. It compares the supplied contract against
that identity before reading a submission. Only descriptive top-level and nested `runtime`
metadata is normalized away; all fixture paths, bytes/hashes, oracle decisions, PTS, references,
protocol fields and limits remain in the canonical comparison. Editing a hold to `PROCESS` and
re-signing the submission's contract hash is therefore rejected.

The evaluator also rejects unsupported schema/status values, missing, unknown, duplicate or
reordered cases/frames, paths escaping the submission root, missing files, artifact hash drift and
non-RGB or wrong-sized outputs. Its only PASS/FAIL field is
`declared_oracle_conformance`. `FAIL` means a submission declaration or supplied file conflicts
with the canonical synthetic oracle; `PASS` means only that those declarations and files conform.
Neither result classifies actual analyzer behavior.

The summary names semantic fields `declared_wrong_reuse_*`, `declared_missed_reuse_*`,
`declared_pts_identity_*`, `declared_reference_identity_*` and
`declared_same_frame_identity_*`. Rates use expected process or hold frames as denominators, but
they are differences between declarations and the oracle—not observed runtime error rates.

Every structurally readable output receives PSNR, global SSIM and edge MAE against its current
high-resolution reference. Spatial rows also include the existing Lanczos measurement. Infinite
PSNR is represented as `psnr_db: null` plus `psnr_is_infinite: true`, keeping the report strict
JSON.

The older 32x24 PPM tool now requires the manifest written by `prepare`, verifies the canonical
fixture bytes and current Anime4K source pin, and requires declared input and output SHA-256 values
for both Android and mpv files. Identical pixels produce `DECLARED_PIXEL_MATCH_ONLY`, never
`PASS` or runtime equivalence. Without a replayable capture receipt, even two identical uniform
PPMs establish only a declared comparison.

## Runtime evidence still required

`runtime_evidence.status` always remains `NOT_BOUND`. A submitted trace SHA-256 is integrity
metadata for alleged bytes; by itself it does not prove that those bytes came from an execution.
An observed runtime claim needs all of the following in a later gate:

- replayable per-frame trace bytes containing actual frame/input/output identity, PTS, decision,
  reference, generation and stream epoch;
- a receipt binding that trace, this canonical contract, output files, runtime configuration,
  executable/App source identity and execution environment;
- validator replay of the trace against every bound artifact and oracle field.

## What this host work proves

Confirmed by source and tests:

- rights-clear deterministic generation covers every named spatial and temporal slice;
- generated contracts are reproducible across independent directories on the same pinned
  Pillow/NumPy environment;
- the pinned Anime4K source identity is checked before preparation;
- an oracle-filled submission can pass only `declared_oracle_conformance`; a trace hash alone
  leaves runtime evidence `NOT_BOUND`;
- declared reuse/PTS/reference mismatches, missing frames, output substitution, and a modified
  cadence oracle with a freshly signed submission hash fail closed;
- the report emits PSNR, global SSIM and edge MAE for all supplied spatial and temporal outputs.

Not visually proven:

- no replayable Anime4K/mpv/Android adapter or cadence-analyzer trace/receipt was captured in this
  host task;
- no target GPU shader equivalence, cadence analyzer result, final display cadence, representative
  anime quality, halo/line/subtitle preference or human rhythm judgment passed;
- no PSNR/SSIM/edge acceptance threshold was invented before paired outputs and human review;
- low-contrast subtitle safety remains an explicit open gate even though the fixture and oracle
  now exist.

Declared-oracle conformance, bound runtime evidence, metric observations and human review remain
separate. A fixture/evaluator test PASS proves the declared-conformance gate works; it is neither
an observed cadence result nor a visual-quality PASS for Anime4K or cadence reuse.
