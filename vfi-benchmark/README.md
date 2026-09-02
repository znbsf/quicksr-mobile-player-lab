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

Generated PNGs, outputs, reports, weights, binaries, APKs, and raw logs must remain under
`local-artifacts/` or another ignored path. The committed candidate manifest records observed
hashes and rights status, but it is not a redistribution grant. Quality fields are reproducible
proxies only; `human_review` remains `pending` until a person inspects the output.
