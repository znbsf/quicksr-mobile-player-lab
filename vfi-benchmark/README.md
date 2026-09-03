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

The same runner accepts an explicitly identified ncnn VFI executable and model layout. Build the
pinned IFRNet-S lower-bound probe and run only the three reviewed levels with:

```powershell
powershell -ExecutionPolicy Bypass -File vfi-benchmark/build_android_ifrnet_probe.ps1 `
  -UpstreamRoot local-artifacts/vfi-mobile/upstream/ifrnet-ncnn-vulkan

python vfi-benchmark/run_vfi_android_resident_matrix.py `
  --serial <one-connected-device-serial> `
  --binary local-artifacts/vfi-mobile/upstream/ifrnet-ncnn-vulkan/build-android-arm64/ifrnet-ncnn-vulkan `
  --model-dir local-artifacts/vfi-mobile/models/IFRNet_S_Vimeo90K `
  --fixture-manifest local-artifacts/vfi-mobile/resident-fixtures/resident-fixture-manifest.json `
  --output-dir local-artifacts/vfi-mobile/device/ifrnet-s-vimeo90k `
  --levels 160x90 256x144 320x180 `
  --candidate-id ifrnet-ncnn-vulkan-s-vimeo90k `
  --source-commit 3592a70355ec011fe7cefb3a9ba08b63d82a2b6d `
  --ncnn-commit 30ab31cc4194f57866ba48753aeceae40e823d81 `
  --libwebp-commit 5a2d929cd8a627d7a342e78ce4603167022b76af `
  --executable-name ifrnet-ncnn-vulkan `
  --remote-model-name IFRNet_S_Vimeo90K `
  --model-param-name ifrnet.param `
  --model-bin-name ifrnet.bin `
  --timing-patch vfi-benchmark/patches/ifrnet-ncnn-vulkan-model-timing.txt
```

The IFRNet model files are intentionally not supplied by this repository. Their expected identities
are recorded in `candidates.json`; availability is not redistribution permission.

Build the exact modern runtime required by RIFE v4.25-lite. The Windows script requires Vulkan
headers and an import/static library supplied from a local SDK or another ignored toolchain path:

```powershell
powershell -ExecutionPolicy Bypass -File vfi-benchmark/build_windows_rife_v425_lite_probe.ps1 `
  -UpstreamRoot local-artifacts/vfi-mobile/upstream/rife-ncnn-vulkan `
  -VulkanIncludeDir <local-vulkan-include-root> `
  -VulkanLibrary <local-vulkan-library>

python vfi-benchmark/run_vfi_host_benchmark.py `
  --fixture-manifest local-artifacts/vfi-mobile/fixtures/fixture-manifest.json `
  --executable local-artifacts/vfi-mobile/upstream/rife-ncnn-vulkan/build-windows-x64/Release/rife-ncnn-vulkan.exe `
  --model-dir local-artifacts/vfi-mobile/upstream/rife-ncnn-vulkan/models/rife-v4.25-lite `
  --candidate-id rife-ncnn-vulkan-v4.25-lite `
  --source-commit 13338e38debe2e400b3eeecf6792312d01a692f9 `
  --output-dir local-artifacts/vfi-mobile/host/rife-v4.25-lite

powershell -ExecutionPolicy Bypass -File vfi-benchmark/build_android_rife_v425_lite_probe.ps1 `
  -UpstreamRoot local-artifacts/vfi-mobile/upstream/rife-ncnn-vulkan
```

When the reviewed physical device is available, run only the same three resident levels:

```powershell
python vfi-benchmark/generate_resident_fixtures.py `
  --output-dir local-artifacts/vfi-mobile/resident-fixtures-rife-v425-lite `
  --levels 160x90 256x144 320x180 `
  --padding-multiple 128

python vfi-benchmark/run_vfi_android_resident_matrix.py `
  --serial <one-connected-device-serial> `
  --binary local-artifacts/vfi-mobile/upstream/rife-ncnn-vulkan/build-android-arm64/rife-ncnn-vulkan `
  --model-dir local-artifacts/vfi-mobile/upstream/rife-ncnn-vulkan/models/rife-v4.25-lite `
  --fixture-manifest local-artifacts/vfi-mobile/resident-fixtures-rife-v425-lite/resident-fixture-manifest.json `
  --output-dir local-artifacts/vfi-mobile/device/rife-v4.25-lite `
  --levels 160x90 256x144 320x180 `
  --candidate-id rife-ncnn-vulkan-v4.25-lite `
  --source-commit 13338e38debe2e400b3eeecf6792312d01a692f9 `
  --ncnn-commit ec19da2b615cc8be438ae3d31fd34fe23df03d52 `
  --libwebp-commit 5abb55823bb6196a918dd87202b2f32bbaff4c18 `
  --remote-model-name rife-v4.25-lite `
  --timing-patch vfi-benchmark/patches/rife-v425-lite-model-timing.txt
```

The source pixels, decisions, and three resolution levels are the same as the frozen resident
fixture; only the declared model padding changes from 32 to the v4.25-lite runtime's actual 128.
The modern runtime still needs the committed ncnn pack8 compatibility patch. The Android build
script applies both that compatibility patch and the timing-only patch, checks all source/model
identities, and refuses an incompatible tree. The 2026-09-03 cycle completed the host gate and
Android cross-build but not device execution; see `rife-v425-lite-evidence-summary.json`.

The runner fails before execution unless the manifest schema and level metadata are exact, every
level has precisely seven input and six ground-truth files with matching bytes/SHA-256/dimensions,
and a fresh prefilter replay matches the recorded decisions. Each input is SHA-256 checked again on
device immediately after push; ground truths are rechecked immediately before scoring. Timing
streams must each contain the unique IDs `0..13`. The raw ignored report records both local fixture
identities and device-side input hashes. It also requires every output hash from the unpolled run to
match the corresponding output from the separate sampled run before scoring.

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
