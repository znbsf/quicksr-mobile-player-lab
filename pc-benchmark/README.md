# PC-first anime super-resolution benchmark

This directory turns the product target into a reproducible host-side contract before adding more Android/QNN variants.

## Scope

- Inputs: 16:9 360p, 480p, 720p and square 360/480/720 sources.
- Canvases: 1920x1080, 2560x1440 and 3840x2160.
- Aspect rule: fit and pad; never stretch square or near-16:9 legacy pixels.
- Planned neural scales: 1.5x, 2x, 3x and 4x. Non-native scales use an explicit neural-plus-linear or cascade route.
- Current executable models: locally exported QuickSRNetSmall 1.5x, 2x, 3x and 4x models on ONNX Runtime CPU, with dynamic-shape route models and fixed 640x360 benchmark models.

The generated synthetic frame is original deterministic code and may be used for pipeline checks. The open-asset path uses a SHA-256-verified Big Buck Bunny frame and 15-frame sequence under CC BY 3.0. Neither is a substitute for a representative anime evaluation set or human review.

## Generate the 18-route matrix

```powershell
python .\pc-benchmark\plan_matrix.py --output .\build\pc-benchmark\route-matrix.json
```

The route matrix separates the quality-oriented chain from a mobile real-time fallback. `scale_component_availability` reports only whether the required scale models exist for PC or Android; it does not claim the complete route has passed on a phone.

## Prepare the local benchmark environment and open assets

```powershell
& .\build\fixed512-python-env\Scripts\python.exe -m pip install -r .\pc-benchmark\requirements.txt
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\fetch_open_assets.py
```

Downloads stay in the Git-ignored `build/pc-benchmark/assets/` cache. The fetcher verifies the single frame by its frozen byte count and SHA-256, and verifies every sequence frame against Xiph's pinned upstream checksum index. Required attribution is stored in `open-assets.json`.

## Export the 1.5x/2x/3x/4x variants

The official checkpoint URLs, byte counts and SHA-256 values are frozen in `model-sources.json`. Checkpoints and generated ONNX files remain ignored local inputs.

```powershell
& .\build\fixed512-python-env\Scripts\python.exe -m pip install -r .\pc-benchmark\requirements-export.txt
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\export_quicksrnet_variants.py
```

The legacy upstream archives contain optimizer state, so current PyTorch cannot read them with `weights_only=True`. The exporter crosses that pickle boundary only after exact URL, byte-count and SHA-256 verification. The independently frozen 2x canonical graph is used as an output-equivalence check.

## Run the fixed 2x PC baseline

Use an environment containing NumPy and ONNX Runtime. The existing local derivation environment is sufficient:

```powershell
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\run_fixed2x_baseline.py
```

Outputs stay under the Git-ignored `build/pc-benchmark/` directory:

- `report.json`: exact model identity, CPU provider, raw timing samples and quality diagnostics;
- `reference.ppm`: synthetic 1280x720 reference;
- `low-resolution.ppm`: deterministic 640x360 area-downsampled input;
- `quicksr-2x.ppm` and `bilinear-2x.ppm`: comparable outputs.

The first report is a pipeline baseline only. It cannot establish real-anime quality, Android QNN speed, display-frame delivery, A/V sync or thermal stability.

## Run the open image, video and 18-route matrix

```powershell
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\run_open_image_benchmark.py
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\run_open_video_benchmark.py
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\run_route_matrix_benchmark.py
```

Repeat `run_open_image_benchmark.py` with `--model` and `--output` for the 1.5x, 3x and 4x registry entries, then generate the comparable matrix:

```powershell
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\summarize_open_image_matrix.py
```

Reports and previews remain in `build/pc-benchmark/`. The observed results and their limits are summarized in [the model and target-resolution plan](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/docs/MODEL_VARIANT_PLAN.md).

## Android emulator build

The production default remains `arm64-v8a`. For the local x86_64 Android Studio AVD, build a CPU/UI validation APK explicitly:

```powershell
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug -PtargetAbi=x86_64
```

This emulator build starts in QuickSR CPU mode and skips QNN mode while cycling player effects. It validates UI, Media3 effect ordering and output sizing only; an x86_64 emulator has no Qualcomm HTP and cannot validate QNN placement or speed.

## Tests

```powershell
& .\build\fixed512-python-env\Scripts\python.exe -m unittest discover -s .\pc-benchmark -p 'test_*.py' -v
```
