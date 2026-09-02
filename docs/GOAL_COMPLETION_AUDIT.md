# PC-first anime SR goal completion audit

Audit date: 2026-09-02

This audit maps the requested product route to current source, generated evidence, and explicit claim boundaries. It treats reproducible host execution, Android functional validation, physical-device performance, and publication safety as separate gates.

## Requirement-to-evidence map

| Requested outcome | Authoritative implementation or evidence | Result |
| --- | --- | --- |
| Reproducible 16:9 360p/480p/720p and 1:1 360/480/720 inputs to 1080p/1440p/4K | `pc-benchmark/anime-targets.json` plus `plan_matrix.py`; regenerated audit plan contains 6 sources, 3 canvases and exactly 18 routes; tests enforce fit-and-pad with no square stretching | PASS |
| Rights-clear image and video-style inputs | `pc-benchmark/open-assets.json` freezes byte counts, SHA-256, attribution and CC BY 3.0/4.0 evidence for Big Buck Bunny, two Pepper&Carrot works and a 15-frame animation sequence; downloader keeps media outside Git | PASS |
| Deterministic clean and legacy degradation | `degradation-profiles.json` freezes clean Lanczos and blur 0.6 plus JPEG Q35 inputs; the corpus runner records the selected profile in every case | PASS |
| ONNX inference and quality/performance reports | The local corpus report contains 72 cases over all 18 route IDs and both degradations. Its SHA-256 is `cb7a53b9c29ecb4cf80dd649beaa4179c60a94bd40906249e406ca21c3785ca4`; the linked summary hash matches. Overall: QuickSR PSNR wins 37/72, median chain 492.55285 ms, maximum 3440.2168 ms | PASS, PC CPU scope |
| Pluggable 1.5x/2x/3x/4x model route | `model-sources.json`, `model-registry.json`, exporter and route runner select models by scale. Current local dynamic and fixed model bytes independently match all eight registered SHA-256 values | PASS, weights remain local-only |
| Android produces the actual selected output size | Media3 `Presentation` establishes the profile canvas before `QuickSrVideoEffect`. Tests pin true neural 1920x1080 and 2560x1440 dimensions and distinguish the 1920x1080 neural to 3840x2160 display fallback | PASS |
| Android Studio emulator validation | API 35 x86_64 completed the 720p/1080p/1440p and 4K-display functional paths. v0.14.0 additionally emitted 49 structured 1080p CPU frame samples and rejected a QNN benchmark request because HTP is unavailable | PASS, functional CPU scope only |
| Phone QNN route prioritizes 1080p and stages 1440p/4K | `contracts/android-qnn-resolution-plan.json` marks 1080p primary, 1440p experimental and 4K display fallback. One physical arm64 Qualcomm device completed 11 rights-clear clips through 720p→1080p with 22/22 functional PASS reports, all `offline`; the gated 1440p and 4K display-fallback cases also functionally passed. The runner rejects emulators/unbound media and separates functional PASS from realtime-30/realtime-24/offline classification | PASS, single-device offline scope |
| Source-only public publication gate | `.gitignore`, `PUBLICATION_BOUNDARY.md`, the fail-closed scanner, negative fixtures and GitHub `source-safety` exclude weights, APKs, media, raw device evidence, credentials and machine paths | PASS; repository visibility remains private |

## Reproduction and validation commands

```powershell
& .\build\fixed512-python-env\Scripts\python.exe -m unittest discover -s .\pc-benchmark -p 'test_*.py' -v
python -m unittest discover -s .\scripts -p 'test_*.py' -v
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug -PtargetAbi=x86_64
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug -PtargetAbi=arm64-v8a
.\scripts\verify-publication.ps1
.\scripts\verify-publication.test.ps1
```

For a connected Qualcomm phone with an already bound rights-clear local clip:

```powershell
$receiptPath = '.\local-artifacts\mobile-subset\push-receipt-<clip>.json'
$receipt = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json

.\scripts\run-android-qnn-resolution-matrix.ps1 `
  -ApkPath .\app\build\outputs\apk\debug\app-debug.apk `
  -VideoUri $receipt.mediaStoreUri `
  -MediaRegistrationReceipt $receiptPath
```

Raw benchmark assets, weights, APKs and device reports are intentionally ignored. Reproduction therefore requires independently qualified local model files and downloaded rights-clear assets whose hashes match the committed registries.

## Claims that remain deliberately open

- The 2026-09-02 phone evidence is one-device functional/offline scope. It does not establish realtime, thermal stability, final-display latency, power, memory pressure, universal device support, or per-node QNN placement. The validated tensor comparisons are observed CPU-vs-Android-QNN results, not P4/full-video/display-quality qualification; see [Android mobile subset validation](ANDROID_MOBILE_SUBSET_VALIDATION.md).
- The 4K route is a 1080p neural result scaled on the GPU, not native 4K neural inference.
- The small rights-clear corpus is useful for reproducible engineering but is not representative of all commercial Japanese anime, legacy codecs, subtitles or temporal artifacts.
- Passing source-safety means the repository is safe to consider for public visibility; it does not grant redistribution rights for models, vendor binaries, media or APKs and does not itself change the private repository setting.

These open claims do not replace or weaken the delivered product route. They prevent the implemented PC-first workflow and staged phone plan from being overstated as universal real-time or quality proof.
