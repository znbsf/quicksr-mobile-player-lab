# Offline anime VFI probe

This directory is a source-only, opt-in evaluation path. It does not register a Media3 effect,
change `QuickSrVideoEffect`, produce frames in normal playback, or enable a default smooth mode.

The stateful prefilter consumes decoded frames in presentation order. A stream-epoch or generation
change first invalidates the previous frame. Exact/near holds and hard cuts are then bypassed. Only
a genuine distinct drawing in the same stream and generation may invoke an external VFI process.

Generate the deterministic project-owned fixture and run tests:

```powershell
python vfi-benchmark/generate_rights_clear_fixtures.py --output-dir local-artifacts/vfi/fixtures
python -m unittest discover -s vfi-benchmark -p "test_*.py"
python vfi-benchmark/validate_candidates.py
```

Run an upstream ncnn Vulkan executable (example paths are intentionally ignored and local):

```powershell
python vfi-benchmark/run_vfi_host_benchmark.py `
  --fixture-manifest local-artifacts/vfi/fixtures/fixture-manifest.json `
  --executable local-artifacts/vfi-upstream/rife-release/rife-ncnn-vulkan-20221029-windows/rife-ncnn-vulkan.exe `
  --model-dir local-artifacts/vfi-upstream/rife-release/rife-ncnn-vulkan-20221029-windows/rife-v4.6 `
  --candidate-id rife-ncnn-vulkan-v4.6 `
  --source-commit a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7 `
  --output-dir local-artifacts/vfi/host/rife-v4.6
```

Build and run the standalone Android probe after cloning the exact RIFE source into an ignored
directory:

```powershell
powershell -ExecutionPolicy Bypass -File vfi-benchmark/build_android_rife_probe.ps1 `
  -UpstreamRoot local-artifacts/vfi-upstream/rife-ncnn-vulkan

python vfi-benchmark/run_vfi_android_probe.py `
  --serial <one-connected-device-serial> `
  --binary local-artifacts/vfi-upstream/rife-ncnn-vulkan/build-android-arm64/rife-ncnn-vulkan `
  --model-dir local-artifacts/vfi-upstream/rife-ncnn-vulkan/models/rife-v4.6 `
  --frame0 local-artifacts/vfi/fixtures/distinct_0.png `
  --frame1 local-artifacts/vfi/fixtures/distinct_1.png `
  --ground-truth local-artifacts/vfi/fixtures/distinct_mid_gt.png `
  --source-commit a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7 `
  --output-dir local-artifacts/vfi/device/rife-v4.6
```

Generate the resident-process resolution ladder. Every level contains seven distinct project-owned
source frames and six known midpoints; the manifest must classify all six adjacent pairs as
`INTERPOLATE / DISTINCT_DRAWING`:

```powershell
python vfi-benchmark/generate_resident_fixtures.py `
  --output-dir local-artifacts/vfi/resident-fixtures `
  --levels 160x90 256x144 320x180 480x270 640x360
```

Run one unpolled latency process and one separate PSS/RSS-sampled process per level. The upstream
directory mode initializes Vulkan and loads the model once, then processes all seven input frames.
The first `timestep=0.5` call is warmup; the following five midpoint calls form the stable latency
summary. Memory polling is confined to the second run and excluded from that summary:

```powershell
python vfi-benchmark/run_vfi_android_resident_matrix.py `
  --serial <one-connected-device-serial> `
  --binary local-artifacts/vfi-upstream/rife-ncnn-vulkan/build-android-arm64/rife-ncnn-vulkan `
  --model-dir local-artifacts/vfi-upstream/rife-ncnn-vulkan/models/rife-v4.6 `
  --fixture-manifest local-artifacts/vfi/resident-fixtures/resident-fixture-manifest.json `
  --output-dir local-artifacts/vfi/resident-device `
  --levels 160x90 256x144 320x180 480x270 640x360
```

The runner fails before execution unless the manifest schema and level metadata are exact, every
level has precisely seven input and six ground-truth files with matching bytes/SHA-256/dimensions,
and a fresh prefilter replay matches the recorded decisions. Each input is SHA-256 checked again on
device immediately after push; ground truths are rechecked immediately before scoring. Timing
streams must each contain the unique IDs `0..13`. The raw ignored report records both local fixture
identities and device-side input hashes.

The report keeps cold process wall time, Vulkan initialization, model loading, midpoint warmup,
five stable midpoint calls, PNG decode/encode, sampled memory, input/padded dimensions, temperature
proxies, output hashes, and known-midpoint quality proxies separate. The upstream pipeline overlaps
decode/model/encode stages, so their individual times are not additive to whole-process time.

The build script also refuses an upstream tree carrying the older `VFI_MODEL_WALL_NS=`-only patch.
Use a clean checkout at the pinned commit in that case; the script does not reset or rewrite it.

Generated PNGs, outputs, reports, weights, binaries, APKs, and raw logs must remain under
`local-artifacts/` or another ignored path. The committed candidate manifest records observed
hashes and rights status, but it is not a redistribution grant. Quality fields are reproducible
proxies only; `human_review` remains `pending` until a person inspects the output.
