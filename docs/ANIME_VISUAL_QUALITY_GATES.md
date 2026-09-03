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

The command also writes `submission-template.json`. A real offscreen GL, mpv or Android capture
adapter must replace every template marker with observed values and place its RGB PNG outputs
under the submission directory. Copying the oracle fields without observing the adapter is not
evidence.

## Fail-closed evaluation

```powershell
python .\pc-benchmark\anime_visual_quality_gate.py evaluate `
  --contract .\build\pc-benchmark\anime-visual-quality-gate\contract.json `
  --submission .\build\pc-benchmark\anime-visual-quality-gate\observed\submission.json `
  --output .\build\pc-benchmark\anime-visual-quality-gate\observed\report.json
```

The evaluator rejects unsupported schema/status values, a mismatched contract hash, missing,
unknown, duplicate or reordered cases/frames, paths escaping the submission root, missing files,
and non-RGB or wrong-sized outputs. The machine gate is `FAIL` if any observed input hash is not
the contract's same-frame identity, an output hash is false, a PTS differs, a processed-frame
reference differs, a material change is reused, an exact hold is unnecessarily processed, or a
claimed reused output is not byte-identical to its referenced processed output. The CLI returns a
nonzero exit code for a completed `FAIL` report and for structural rejection.

The summary records both counts and rates: wrong reuse is divided by expected process frames, and
missed reuse is divided by expected hold/reuse frames. First-frame processing remains part of the
safety denominator.

Every structurally readable output receives PSNR, global SSIM and edge MAE against its current
high-resolution reference. Spatial rows also include the existing Lanczos measurement. Infinite
PSNR is represented as `psnr_db: null` plus `psnr_is_infinite: true`, keeping the report strict
JSON.

The older 32x24 PPM equality gate remains available through
`scripts/anime4k_reference_fixture.py`. Its comparison now reports the same three diagnostics and
returns nonzero on any non-exact Android/mpv pixel difference.

## What this host work proves

Confirmed by source and tests:

- rights-clear deterministic generation covers every named spatial and temporal slice;
- generated contracts are reproducible across independent directories on the same pinned
  Pillow/NumPy environment;
- the pinned Anime4K source identity is checked before preparation;
- an ideal bound submission passes, while wrong reuse, missed reuse, one-microsecond PTS drift,
  wrong reference identity, missing frames and contract-hash substitution fail closed;
- the report emits PSNR, global SSIM and edge MAE for all supplied spatial and temporal outputs.

Not visually proven:

- no Anime4K/mpv/Android adapter output was captured in this host task;
- no target GPU shader equivalence, cadence analyzer result, final display cadence, representative
  anime quality, halo/line/subtitle preference or human rhythm judgment passed;
- no PSNR/SSIM/edge acceptance threshold was invented before paired outputs and human review;
- low-contrast subtitle safety remains an explicit open gate even though the fixture and oracle
  now exist.

The machine result, metric observations and human review must remain separate. A fixture/evaluator
test PASS proves the gate works; it is not a visual-quality PASS for Anime4K or cadence reuse.
