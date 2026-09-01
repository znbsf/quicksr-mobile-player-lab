# PC-first anime super-resolution benchmark

This directory turns the product target into a reproducible host-side contract before adding more Android/QNN variants.

## Scope

- Inputs: 16:9 360p, 480p, 720p and square 360/480/720 sources.
- Canvases: 1920x1080, 2560x1440 and 3840x2160.
- Aspect rule: fit and pad; never stretch square or near-16:9 legacy pixels.
- Planned neural scales: 1.5x, 2x, 3x and 4x. Non-native scales use an explicit neural-plus-linear or cascade route.
- Current executable baseline: the local source-qualified 640x360 to 1280x720 QuickSRNetSmall 2x model on ONNX Runtime CPU.

The generated synthetic frame is original deterministic code and may be used for pipeline checks. It is not a substitute for a licensed anime evaluation set or human review.

## Generate the 18-route matrix

```powershell
python .\pc-benchmark\plan_matrix.py --output .\build\pc-benchmark\route-matrix.json
```

The route matrix separates the quality-oriented chain from a mobile real-time fallback. `availability` reports whether the needed neural scale is already integrated.

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
& .\build\fixed512-python-env\Scripts\python.exe -m unittest .\pc-benchmark\test_pc_benchmark.py -v
```
