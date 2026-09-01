# Anime SR model and target-resolution plan

Status date: 2026-09-01

## What is executable now

The PC path now exports and verifies official QuickSRNetSmall checkpoints for
1.5x, 2x, 3x and 4x. Both dynamic-shape ONNX models and fixed 640x360 benchmark
models stay local; the public repository stores only source URLs, hashes,
export code and reports. The exported 2x graph matched the frozen canonical
2x ONNX exactly on the validation tensor (`max_abs_error = 0`).

All 18 combinations of 360p/480p/720p, 16:9/square and
1080p/1440p/2160p have executed on one verified CC BY 3.0 animation frame.
Square inputs are center-cropped to a square source and fit into the target
canvas with pillarboxing; they are never stretched.

| Scale | PC fixed input/output | Clean p50 | Clean QuickSR - Lanczos PSNR | Blur + JPEG Q35 delta |
| ---: | --- | ---: | ---: | ---: |
| 1.5x | 640x360 -> 960x540 | 151.7 ms | +1.127 dB | -0.255 dB |
| 2x | 640x360 -> 1280x720 | 188.4 ms | +1.039 dB | -0.363 dB |
| 3x | 640x360 -> 1920x1080 | 199.8 ms | +0.833 dB | -0.460 dB |
| 4x | 640x360 -> 2560x1440 | 232.5 ms | +0.862 dB | -0.516 dB |

Across the clean 18-route run, QuickSR won PSNR on 12 routes and
Lanczos won or tied on 6. Median complete-chain time was 494.8 ms and the
slowest route, 854x480 to 4K through 3x then 1.5x, took 3313.1 ms on PC CPU.
These timings are pipeline evidence, not mobile QNN predictions.

The result is deliberately mixed: QuickSRSmall improves clean low-resolution
inputs, but loses to Lanczos on the current blur/JPEG degradation and on the
720p clean routes in this single-frame check. A production anime model needs
training or fine-tuning for compression, ringing, blur, grain and line art;
changing only the output scale cannot solve that mismatch.

## Product routing

| Source | 1080p quality | 1440p quality | 4K quality | Mobile fallback with current 2x app model |
| --- | --- | --- | --- | --- |
| 360p 16:9 | 3x | 4x | 3x then 2x | 2x then GPU resize |
| 480p 16:9 | 3x then 0.75x fit | 3x plus pixel-aspect fit | 3x then 1.5x plus fit | 2x then GPU resize |
| 720p 16:9 | 1.5x | 2x | 3x | 2x then GPU resize |
| square 360/480/720 | same scale logic, then pillarbox | same | same | 2x then GPU resize and pillarbox |

“Scale component available” does not mean the Android App has a validated
target canvas for every row. Android currently integrates and has device smoke
evidence only for the 640x360 -> 1280x720 2x path. The other scales are PC-only
until their fixed graphs, memory limits, HTP placement, correctness and display
paths pass separately.

## Real-time budget

- 24 fps allows 41.67 ms per frame; 30 fps allows 33.33 ms.
- The observed phone 720p smoke sampled about 9 ms in ORT/QNN, 10 ms in output
  conversion and 22 ms total, but that is one periodic sample rather than p95.
- The PC fixed-model results are 4.3-6.6 fps and therefore not real-time.
- 1440p has 4x and 4K has 9x as many output pixels as 720p. Even if NPU compute
  remains acceptable, float output conversion, CPU/GPU transfer and texture
  upload can dominate.

The general architecture remains hardware MediaCodec + Media3 for decode,
timing and presentation; GPU for resize/colorspace/compositing; NPU for the
neural core. Replacing the whole player with C does not remove NPU compute or
memory traffic. Native C++/NEON, Vulkan/OpenGL compute, PBO and shared I/O are
useful only at measured conversion/copy boundaries.

## Model candidates, in order

1. **QuickSRNetSmall 1.5x/2x/3x/4x** — now the executable PC baseline and the
   first QNN staging candidate. Qualcomm's shared recipe confirms pretrained
   checkpoints for 2x/3x/4x, while the pinned AIMET release also contains the
   verified 1.5x checkpoint used locally.
2. **XLSR and SESR-M5** — Qualcomm describes both as lightweight real-time
   super-resolution models. They should be compared against Small on the same
   degraded animation matrix before any Android integration.
3. **QuickSRNetMedium/Large** — likely quality steps with higher compute; useful
   for offline image mode or a quality profile, not assumed real-time.
4. **Real-ESRGAN-General-x4v3** — a stronger blind-restoration candidate for
   compressed legacy images and offline video export. It is not the first
   choice for full-frame live playback because its compute and memory cost are
   much larger.

Qualcomm AI Hub's supported workflow is to compile for a selected device and
runtime, profile on physical hardware, and run inference for numerical checks.
Its model repository also exposes float and W8A8 variants. The next phone work
should therefore compare float versus W8A8, inspect HTP placement, and test QNN
context binaries/shared I/O rather than merely switching a generic runtime flag.

Primary references:

- [Qualcomm AI Hub Models super-resolution catalog](https://github.com/qualcomm/ai-hub-models#readme)
- [QuickSRNetSmall recipe and export flow](https://github.com/qualcomm/ai-hub-models/tree/main/src/qai_hub_models/models/quicksrnetsmall)
- [Supported QuickSR scale factors in Qualcomm's shared model template](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/templates/super_resolution/model.py)
- [XLSR recipe](https://github.com/qualcomm/ai-hub-models/tree/main/src/qai_hub_models/models/xlsr)
- [SESR-M5 recipe](https://github.com/qualcomm/ai-hub-models/tree/main/src/qai_hub_models/models/sesr_m5)
- [Real-ESRGAN-General-x4v3 recipe](https://github.com/qualcomm/ai-hub-models/tree/main/src/qai_hub_models/models/real_esrgan_general_x4v3)
- [Qualcomm AI Hub compile/profile/inference workflow](https://app.aihub.qualcomm.com/docs/)

## Evaluation assets

The current reproducible asset is a 15-frame Big Buck Bunny sequence from the
Xiph mirror. Every frame is checked against the pinned upstream SHA-256 index;
the movie is CC BY 3.0 and requires the recorded Blender Foundation attribution.
It validates an open animation pipeline but is not Japanese anime.

The dataset ladder should be:

1. rights-clear open animation frames and clips, committed only as download
   manifests and hashes;
2. a rights-reviewed open line-art/anime-style set, if one can be shown to allow
   the intended evaluation and redistribution;
3. private, user-owned anime clips for local acceptance only, never committed;
4. human side-by-side review for line integrity, halos, ringing, texture
   hallucination, temporal shimmer and subtitle damage.

Danbooru-style collections and clips extracted from commercial anime are not
treated as public benchmark assets merely because they are easy to download.
