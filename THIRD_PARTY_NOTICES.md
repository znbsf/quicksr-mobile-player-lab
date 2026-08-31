# Third-party notices

This file records the upstream components referenced by the source-only
QuickSR mobile player lab. It is not a grant to redistribute model weights,
Qualcomm runtime binaries, or an APK that embeds either of them. The exact
upstream license text and release metadata control if this summary differs
from them.

## QuickSRNet source references

### Qualcomm AI Hub Models

- Upstream: <https://github.com/qualcomm/ai-hub-models>
- Pinned source revision used by the experiment:
  `5975a79b55b40f5cbc61f3ac5e52abe47d9d8bd5`
- License: BSD 3-Clause
- Pinned license text:
  <https://raw.githubusercontent.com/qualcomm/ai-hub-models/5975a79b55b40f5cbc61f3ac5e52abe47d9d8bd5/LICENSE>
- Copyright: Qualcomm Technologies, Inc. and/or its subsidiaries.

Redistribution of upstream source or a derivative that contains upstream
source must retain the copyright notice, BSD conditions, and disclaimer.
Qualcomm names may not be used to endorse this project.

### AIMET Model Zoo QuickSRNet

- Upstream: <https://github.com/quic/aimet-model-zoo>
- Pinned source revision used by the experiment:
  `1bd2bf5b17cdda9251437c444009b29e1a25054b`
- License: BSD 3-Clause
- Pinned license text:
  <https://raw.githubusercontent.com/quic/aimet-model-zoo/1bd2bf5b17cdda9251437c444009b29e1a25054b/LICENSE.md>
- Copyright: the respective contributors identified by the upstream project.

The official QuickSRNet material states that the checkpoint was trained on
DIV2K. The DIV2K site makes the dataset available for academic research only:
<https://data.vision.ee.ethz.ch/cvl/DIV2K/>. A repository-level BSD license and
a dataset-use restriction are different rights. No separate, reviewed grant
for public or commercial redistribution of the checkpoint or derived ONNX
weights has been established here.

Consequently, this repository records source URLs, revisions, byte counts,
and SHA-256 values but does not publish the checkpoint, canonical ONNX model,
fixed-shape derived ONNX models, or an APK containing those weights.

## ONNX Runtime

- Upstream: <https://github.com/microsoft/onnxruntime>
- Android dependency used by the experiment:
  `com.microsoft.onnxruntime:onnxruntime-android:1.26.0`
- License: MIT
- License text: <https://github.com/microsoft/onnxruntime/blob/main/LICENSE>
- Third-party notices:
  <https://github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt>

The dependency is resolved from its upstream Maven repository. Its AAR,
native libraries, extracted files, and generated APK are not stored in this
source repository.

## ONNX Runtime QNN execution-provider plugin

- Upstream: <https://github.com/onnxruntime/onnxruntime-qnn>
- Android dependency used by the experiment:
  `com.qualcomm.qti:onnxruntime-android-qnn:2.5.0`
- Maven metadata:
  <https://central.sonatype.com/artifact/com.qualcomm.qti/onnxruntime-android-qnn/2.5.0>
- License identified by the upstream repository and Maven POM: MIT
- License text:
  <https://github.com/onnxruntime/onnxruntime-qnn/blob/main/LICENSE>

Any source substantially copied from an upstream example must retain the
applicable MIT notice. Referencing an example or declaring the Maven
coordinate does not change the license of the separate QNN runtime payload.

## Qualcomm QNN Runtime

- Android dependency used by the experiment:
  `com.qualcomm.qti:qnn-runtime:2.49.0`
- Maven metadata:
  <https://central.sonatype.com/artifact/com.qualcomm.qti/qnn-runtime/2.49.0>
- License named by the Maven POM: Qualcomm AI Hub Model License
- License URL recorded by the Maven POM:
  <https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf>
- Source-control metadata in that POM: non-public

The QNN Runtime is a separately licensed vendor binary, not an MIT or
BSD-licensed part of this repository. This repository may declare its Maven
coordinate for a local build, but it must not commit or mirror the AAR,
extracted shared libraries, DSP/HTP stub or skel libraries, or a generated APK.
Anyone resolving or packaging the dependency is responsible for reviewing and
accepting the controlling Qualcomm terms. APK distribution remains a separate
license-review gate.

## AndroidX Media3

- Upstream: <https://github.com/androidx/media>
- Android dependencies used by the experiment:
  - `androidx.media3:media3-exoplayer:1.11.0`
  - `androidx.media3:media3-ui:1.11.0`
  - `androidx.media3:media3-effect:1.11.0`
- License: Apache License 2.0
- License text: <https://github.com/androidx/media/blob/release/LICENSE>

Media3 supplies the player, UI, GL effect interfaces, byte-buffer bridge, and
Lanczos baseline used by the demo. Its resolved AARs and generated application
artifacts are not stored in this source repository.

## Build and test dependencies

Android build tools, Gradle, and JUnit are resolved from their upstream
repositories and are not vendored here. The standard Gradle 8.14 wrapper JAR
is included; its properties use the official HTTPS distribution endpoint and
pin the published distribution SHA-256. Their own licenses and notices
continue to apply.

## Project boundary

Original project files are offered under the top-level MIT license. That
license does not relicense third-party source, model weights, dependencies,
datasets, or media. This notice also does not certify patent rights, dataset
rights, model-weight rights, trademark rights, or commercial fitness.
