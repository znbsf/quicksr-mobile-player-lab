# Anime4K Android GPU integration

Status date: 2026-09-03

This change prepares a selectable Media3 player mode for Anime4K v4.0.1
Upscale CNN x2 Small. It keeps MediaCodec decoding and passes GL textures
directly through the effect. It does not change QuickSR models, QNN tuning,
queue policy or cadence handling.

## Pinned upstream identity and license

- Repository: <https://github.com/bloc97/Anime4K>
- Release: `v4.0.1`
- Commit: `4029bf701ecaa15f163cdc49cffe5501c1acf410`
- File: `glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl`
- Bytes: 18,638
- SHA-256:
  `4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4`
- License: MIT, Copyright (c) 2019-2021 bloc97

The exact upstream text and notice are retained under
`app/src/main/assets/anime4k/`. Both Gradle and the runtime parser verify its
identity. The source license allows redistribution with the notice retained;
the training-corpus identity and rights remain an open provenance question.

## Implemented pass graph

The adapter consumes every pass in the pinned Small shader:

1. `Conv-4x3x3x3`: input RGB to four signed feature channels.
2. `Conv-4x3x3x8`: positive/negative feature split to four channels.
3. `Conv-4x3x3x8`: second hidden convolution.
4. `Conv-4x3x3x8`: final packed residual convolution.
5. `Depth-to-Space`: 2x sub-pixel selection plus bilinear original-frame
   residual.

Passes 1-4 ping-pong between two RGBA16F textures. Pass 5 reads the packed
feature texture and the original Media3 input texture. No pass maps a texture
to CPU memory or enters ONNX Runtime/QNN.

This consumes the complete coefficient and pass topology of the pinned x2
Small source. It is not yet an output-equivalence claim. Media3 1.11.0's
`DefaultVideoFrameProcessor.Factory.Builder` initializes
`sdrWorkingColorSpace` to `WORKING_COLOR_SPACE_DEFAULT`; ExoPlayer's default
effect route constructs that builder through
`ReflectiveDefaultVideoFrameProcessorFactory`. For SDR/SRGB input, Media3's
external and internal sampling shaders preserve nonlinear electrical RGB in
this mode. The App now explicitly installs a video renderer whose frame
processor pins that same working space. The Anime4K adapter therefore samples
and returns those values directly, with no extra OETF/EOTF. This also avoids
the invalid alternative of assuming Media3 linear SDR is sRGB-linear: Media3's
linear option uses the SMPTE 170M transfer for SDR video.

The adapter still preserves source alpha and replaces dynamic vector indexing
with explicit selection for OpenGL ES portability. Equivalence to the pinned
mpv shader remains open until a same-frame pixel comparison passes.

## Size, color and edge contract

- The selected 720p, 1080p or 1440p target is divided by two.
- Media3 `Presentation` creates that half-size input canvas while preserving
  aspect ratio.
- Anime4K produces exactly 2x width and height.
- Configuration rejects non-positive dimensions and output larger than the
  device `GL_MAX_TEXTURE_SIZE`.
- Model execution requires an ES 3+ context and either
  `GL_EXT_color_buffer_half_float` or `GL_EXT_color_buffer_float`; otherwise
  the effect reports model inactive and uses its bilinear fallback.
- Every 3x3 convolution sample is explicitly clamped to the nearest valid
  texel center.
- HDR/BT.2020 is not treated as validated. HDR input selects the internal GPU
  bilinear 2x fallback.

## Failure and lifecycle behavior

- A missing or modified upstream asset fails the pinned SHA-256 check.
- Asset parse, shader compile or program link failure selects a GPU bilinear
  2x fallback inside the same effect.
- RGBA16F intermediate allocation failure selects the same fallback; the
  final SDR output pool remains RGBA8 so fallback does not require renderable
  half-float textures.
- Texture/FBO creation is transactional. A `glTexImage2D`, attachment or FBO
  completeness failure deletes every object created so far before fallback.
- A GL error during the model passes switches the current and later frames to
  the same fallback and reports that state to the UI.
- Permanent fallback deletes all five model programs immediately. If fallback
  creation, Media3 output-pool allocation or fallback drawing still fails,
  the Activity removes Anime4K, installs GPU Lanczos, prepares again and seeks
  back to the previous playback position.
- Intermediate FBOs, RGBA16F textures, all five model programs, the fallback
  program and Media3's output pool are explicitly released.

The fallback is availability behavior, not an Anime4K result. The UI separately
labels `model active`, internal GPU bilinear fallback, and app-level Lanczos
recovery.

## Fixed same-frame reference gate

`scripts/anime4k_reference_fixture.py` prepares a deterministic opaque 32x24
P6 PPM containing electrical mid-gray, gradients, thin lines and subtitle-like
edges. It verifies the pinned shader hash and writes generated media only to a
caller-selected ignored directory:

```powershell
python scripts/anime4k_reference_fixture.py prepare `
  --shader app/src/main/assets/anime4k/Anime4K_Upscale_CNN_x2_S.txt `
  --output-dir build/anime4k-reference
```

The pinned mpv route and Android adapter must render that exact input to 64x48
P6 PPM without alpha compositing. Compare the two captures with:

```powershell
python scripts/anime4k_reference_fixture.py compare `
  --android build/anime4k-reference/android-output.ppm `
  --mpv build/anime4k-reference/mpv-output.ppm
```

The report freezes both pixel hashes, exact equality, uint8 MAE, maximum channel
error and mismatching-pixel count. Threshold acceptance is deliberately not
invented before the first paired capture; until a reviewed threshold or exact
match passes, mpv equivalence remains open.

## Evidence status

Confirmed by source inspection and host tests:

- upstream text identity and five-pass parsing;
- exact target/input dimension contract;
- generated GLES source contains all four convolution bindings, original-frame
  residual, default-working-color direct sampling and explicit packed-channel
  selection;
- Java compilation and unit-level mode/key contracts.
- 76 Java tests, Android lint, and x86_64 plus arm64-v8a debug assembly pass;
  the arm64 APK contains the same 18,638-byte source asset and SHA-256. APKs
  remain ignored local build products.
- The five model fragments generated by the compiled Java adapter and the
  `mediump` fallback compile and link in the Android Emulator bundle's
  SwiftShader OpenGL ES 3 EGL environment (6/6 PASS). That same host context
  exposes a half-float color-buffer extension and reports an RGBA16F probe FBO
  complete. This is a DLL-level host shader smoke, not an App run.

Still open until a device is available:

- actual GLES compile/link and texture execution on the target GPU;
- execution of the prepared fixed opaque-frame comparison, followed by clean,
  compressed, subtitle and edge fixtures;
- evidence that RGBA16F allocation succeeds for every target;
- per-pass and end-to-end GPU timings, allocation peak, sustained thermals,
  dropped frames and final displayed-frame inspection;
- HDR behavior beyond the declared fallback.

No physical-device command, APK installation or adb action belongs to this
host-only preparation round.
