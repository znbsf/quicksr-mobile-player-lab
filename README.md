# QuickSR Mobile Player Lab

面向 Android 的图片与视频超分实验 App，重点验证一条可落地的动漫超分路线：先在 PC 上完成倍率、退化、画质和性能筛选，再把合适的模型放到 Qualcomm QNN HTP/NPU 上做实时播放验证。

当前版本：**v0.15.0**。仓库采用 **source-only** 策略，不提交未授权模型权重、APK、测试媒体、Qualcomm 二进制或原始设备日志。唯一随源码保留的神经 shader 是经单独审查、保留 MIT notice 且由 commit/bytes/SHA-256 固定的 Anime4K x2 Small 上游文本。

## 核心能力

- 图片和视频超分；
- 覆盖 16:9 与方形的 360p、480p、720p 输入；
- 输出路线覆盖 1080p、1440p 和 4K 显示；
- 集成 QuickSRNetSmall 1.5×、2×、3×、4× 模型流程；
- 支持 ONNX Runtime CPU、GPU Lanczos、GPU-resident Anime4K x2 Small 和 Qualcomm QNN HTP/NPU；
- 提供默认关闭的动漫 cadence 感知超分复用实验：不插帧、不改 PTS，最多连续复用 2 帧；
- 建立 18 条 PC 动漫超分路线和 72 个权利清晰评测案例；
- 自动统计 PSNR、SSIM、边缘误差、阶段耗时、p50/p95 以及 24/30 FPS 性能等级。

### Android 视频档位

| 档位 | 神经输入与输出 | 定位 |
| --- | --- | --- |
| 720p | `640×360 → 1280×720`，2× | 当前默认与已有真机基线 |
| 1080p | `640×360 → 1920×1080`，3× | 手机 QNN 首要验证档 |
| 1440p | `640×360 → 2560×1440`，4× | 高分实验档 |
| 4K 显示 | `640×360 → 神经 1920×1080 → GPU 3840×2160` | 显示保底，不是原生神经 4K |

另外保留 128p、288p、512p、576p、720p 和 1024p 等静态模型档，用于质量、内存和性能对照。图片路径目前执行完整图片 2×，支持 CPU/QNN 后端和分块处理。

## 为什么选择这些技术

- **Media3/ExoPlayer：**提供硬件 MediaCodec 解码、时间轴、A/V 同步、seek、Surface 和生命周期管理。现有观测表明主要开销在 GL readback、Tensor 转换、推理、输出转换和排队，因此整体重写 C 播放器不是第一优先级。
- **ONNX Runtime：**PC、Android CPU 和 QNN 共用 ONNX 模型与 Java API，便于先做 PC golden/质量筛选，再迁移到手机。
- **Qualcomm QNN HTP：**直接使用 Snapdragon 平台的 NPU/HTP，目标是降低逐帧 CPU 负载和功耗。
- **QuickSRNetSmall：**模型体积较小，并有 1.5×/2×/3×/4× 上游 checkpoint，适合移动端多倍率实验。
- **Anime4K x2 Small：**五个 shader pass 全程保留在 GL texture 中，作为不经过 CPU readback/NPU round trip 的动漫线稿候选；单台 Adreno 740 已完成三档 model-active 功能样本，真机同帧画质、GPU timing 与跨设备兼容仍待 A/B。
- **固定 Shape 模型：**减少运行时图变化，便于 QNN graph finalization、Tensor 复用和稳定的内存规划。
- **Media3 `Presentation`：**在推理前建立真正的目标纹理尺寸，避免模型生成 1080p/1440p 后又被缩回源视频尺寸。
- **PC-first：**手机调试成本高，先在 PC 上淘汰画质差、退化不稳或明显无法实时的路线，再进入设备验证。

当前没有证据表明“把中间流程全部换成 C”就能解决问题。若 profiler 证明 NCHW/RGBA 转换或 GL upload 是主要热点，再针对单个环节引入 C++/NEON、compute shader、PBO 或共享缓冲更合理。

## 当前结果

- 18 条路线覆盖：16:9 与 1:1 的 360p/480p/720p → 1080p/1440p/4K；方形素材采用等比缩放加留边，不拉伸。
- 72 个权利清晰案例中，干净输入 QuickSR PSNR 胜 30/36；模糊 0.6 加 JPEG Q35 仅胜 7/36；方形漫画胜 16/18。
- 结论：QuickSRNetSmall 更适合干净线稿和漫画；严重模糊或压缩素材应回退 Lanczos，或换用针对退化训练的模型。
- 已存档的 v0.12.0 单机 720p QNN smoke：`645 帧 / 26.860 秒 = 24.0134 FPS`，四个约 5 秒 MediaCodec 窗口 Drop=0。
- API 35 x86_64 模拟器已验证 1080p、1440p 和 4K 显示路径的实际纹理尺寸，但模拟器 CPU 时间不能外推到手机 NPU。
- v0.14.0 的一台物理 Qualcomm 设备已完成权利清晰 1080p 主档及受门禁 1440p/4K 显示回退功能验证，但全部归类为 `offline`；其他设备、实时与热稳定性仍需逐机验证。
- v0.15.0 的 Anime4K v4.0.1 x2 Small 已在一台 Android 16 / Adreno 740 设备完成 720p/1080p/1440p 有界播放：三档首帧均 model-active、MediaCodec Drop=0、PSS 峰值约 173-175 MiB、温度代理保持 38.9-39.0 C；同位置视觉 A/B 未可靠取得，仍不声称与 mpv 输出等价或具有通用实时性能。
- 由实际 Java 适配器生成的五段 model fragment 与 `mediump` fallback 已在 Android Emulator 随附的 SwiftShader OpenGL ES 3 环境 6/6 compile+link PASS；同一宿主 context 的 half-float 扩展预检和 RGBA16F FBO completeness 也通过。这仍只是 DLL 级主机 smoke，不是模拟器 App 或目标手机执行。

完整证据边界见 [项目状态](docs/STATUS.md) 和 [完成度审计](docs/GOAL_COMPLETION_AUDIT.md)。当前代码接线、已完成/停止/待执行项及工作树顺序见 [实现计划与进展](docs/IMPLEMENTATION_PLAN_AND_PROGRESS.md)。Anime4K pass、颜色适配、回退与真机门禁见 [Android GPU 集成说明](docs/ANIME4K_ANDROID_GPU_INTEGRATION.md)；研究理由和原始任务拆分保留在 [动漫视频实时超分与插帧执行计划](docs/ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)。
cadence 实验的队列、generation、缓存所有权、运行时开关和代理指标边界见 [动漫 cadence 感知复用说明](docs/ANIME_CADENCE_REUSE.md)。

## 支持机型与系统

项目不维护未经验证的 Snapdragon 型号白名单。QNN 能否工作不仅取决于 SoC 名称，也取决于 Android 固件、CDSP/HTP 服务、驱动和 QNN runtime 兼容性。

| 环境 | 支持状态 | 后端与限制 |
| --- | --- | --- |
| Android 8.1+、arm64-v8a、兼容 Qualcomm HTP/CDSP | 目标平台；单台 Adreno 740 已有 Anime4K 三档功能 smoke | CPU、GPU Lanczos、QNN HTP、Anime4K；画质、长时热稳与其他设备仍需逐机验证 |
| Android 8.1+、其他 arm64-v8a 设备 | 可构建，未形成多厂商兼容矩阵 | 以 ONNX Runtime CPU、GPU Lanczos 和待验证 Anime4K 为主；QNN 初始化失败时不能声称 NPU 支持 |
| Android Studio API 35 x86_64 模拟器 | 已验证功能路径 | CPU 与 GPU 功能检查；没有 Qualcomm HTP，不能测试 QNN 性能 |
| armeabi-v7a、32 位 x86、iOS | 不支持 | 当前构建只接受 `arm64-v8a` 或 `x86_64` |

当前范围是本地 SDR、非 DRM 视频。HDR、DRM、直播、复杂字幕以及所有厂商播放器兼容性尚未形成验证矩阵。高分档还会受到设备可用内存、散热和频率策略影响。

## App 操作方法

### 图片超分

1. 启动 App，进入图片区域；
2. 选择 CPU 或 QNN HTP 后端，以及 960/1440/1920 的处理上限；
3. 点击“选择图片并执行整图 2×”；
4. 完成后可保存为 PNG。

### 视频超分

1. 在视频区域选择神经输出档和 QNN tuning；
2. 在原画、GPU Lanczos、Anime4K x2 Small、QuickSR CPU、QuickSR QNN HTP 间切换；
3. 选择本地非 DRM 视频并播放；
4. 根据界面中的排队、输入转换、ORT/QNN run、输出转换和整帧时间判断瓶颈。

720p 是当前稳妥基线；1080p 是下一档首选；1440p 与 4K 显示档应先观察内存、排队和温度，不建议默认开启。

## 构建与安装

### 前置条件

- Windows PowerShell；
- Android Studio 或 Android SDK；
- Java 17+；
- 本地合法取得并通过 SHA-256 验证的 QuickSRNet 模型；
- 构建 QNN APK 前自行审阅 Qualcomm runtime 的许可条款。

模型与 fixed-shape 派生文件不会随 Git 仓库提供。先按 [模型准备说明](models/README.md) 和 [PC benchmark 说明](pc-benchmark/README.md) 准备本地权重；缺少或 hash 不匹配时构建会 fail closed。

### Android Studio

1. 用 Android Studio 打开仓库根目录；
2. 使用 Android Studio 自带 JBR 或 Java 17+；
3. 确认本地模型已经准备完成；
4. Gradle Sync 后选择 arm64 手机运行 `app`。

### PowerShell 构建

arm64/QNN 构建：

```powershell
.\build-local.ps1
```

也可以指定 canonical 2× ONNX 的本地位置：

```powershell
.\build-local.ps1 -ModelPath <local-canonical-onnx>
```

x86_64 模拟器 CPU 构建：

```powershell
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug -PtargetAbi=x86_64
```

安装 arm64 APK：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

APK、模型和设备日志都被 Git 忽略，不应上传到 GitHub Releases，除非模型和 Qualcomm 二进制的再分发许可已经独立审查通过。

## PC-first 评测方法

安装锁定依赖并下载带 hash 校验的开放素材：

```powershell
& .\build\fixed512-python-env\Scripts\python.exe -m pip install -r .\pc-benchmark\requirements.txt
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\fetch_open_assets.py
```

生成 18 条路线并运行完整语料：

```powershell
& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\plan_matrix.py `
  --output .\build\pc-benchmark\route-matrix.json

& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\run_route_matrix_benchmark.py `
  --asset bbb-1080-frame-01000 `
  --asset peppercarrot-confront-the-dragon-4k `
  --asset peppercarrot-imagination-4k-square `
  --degradation clean-lanczos `
  --degradation legacy-soft-jpeg-q35 `
  --output .\build\pc-benchmark\open-anime-corpus-route-matrix

& .\build\fixed512-python-env\Scripts\python.exe .\pc-benchmark\summarize_route_corpus.py `
  --report .\build\pc-benchmark\open-anime-corpus-route-matrix\report.json
```

报告和预览只写入被忽略的 `build/pc-benchmark/`。详细命令见 [PC benchmark README](pc-benchmark/README.md)。

## 真机 QNN 分辨率矩阵

矩阵只接受一台 arm64 Qualcomm 物理手机和已绑定的权利清晰本地素材；模拟器、任意 URI 和未绑定媒体都会被拒绝。先用
[`mobile-rights-clear-subset.json`](contracts/mobile-rights-clear-subset.json)
物化并登记素材，收据及其 URI 保持在 Git 忽略目录：

```powershell
$receiptPath = '.\local-artifacts\mobile-subset\push-receipt-<clip>.json'
$receipt = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json

.\scripts\run-android-qnn-resolution-matrix.ps1 `
  -ApkPath .\app\build\outputs\apk\debug\app-debug.apk `
  -VideoUri $receipt.mediaStoreUri `
  -MediaRegistrationReceipt $receiptPath
```

默认只按 [机器可读计划](contracts/android-qnn-resolution-plan.json) 运行
`720p-baseline -> 1080p-primary`。1080p 必须先有同一素材、同一收据的
功能 PASS 报告，才能显式尝试 1440p 实验档或 4K 显示回退档：

```powershell
.\scripts\run-android-qnn-resolution-matrix.ps1 `
  -ApkPath .\app\build\outputs\apk\debug\app-debug.apk `
  -VideoUri $receipt.mediaStoreUri `
  -MediaRegistrationReceipt $receiptPath `
  -CaseId 1440p-experimental `
  -IncludeExperimental `
  -PrimaryReportPath <matching-ignored-1080p-report>
```

矩阵会丢弃 warm-up 帧并验证：

- 后端确实是 QNN HTP，不能静默回退 CPU；
- tuning、模型尺寸和画布尺寸与计划一致；
- 没有设备错误且样本数达到要求；
- 分别输出功能门禁以及 `effect_proxy_realtime_30`、
  `effect_proxy_realtime_24`、`offline` 性能分类；前两者只覆盖 effect output-submit
  代理，不代表 GPU completion、SurfaceFlinger latch 或最终显示实时。

QNN 严格遥测证明的是会话配置；它不证明每个节点的 EP placement，也不替代
底层 fallback trace。原始日志、收据、张量和报告保存在被 Git 忽略的
`device-results/` 或 `local-artifacts/`。

2026-09-02 的权利清晰移动子集实机结果为 11/11 720p 与 11/11 1080p
功能 PASS，全部为 `offline`；受门禁的 1440p 与 4K 显示回退也为功能 PASS/
`offline`。27 个合同选帧均完成 Android QNN 对 PC CPU 的数值比较（零失配、
零非有限值）并生成 Lanczos 基线。完整边界、指标和复现步骤见
[Android mobile subset validation](docs/ANDROID_MOBILE_SUBSET_VALIDATION.md)。

## 上游项目

| 组件 | 上游 | 本项目用途 |
| --- | --- | --- |
| QuickSRNetSmall checkpoints | [Qualcomm AIMET Model Zoo](https://github.com/quic/aimet-model-zoo/tree/develop/aimet_zoo_torch/quicksrnet) | 1.5×/2×/3×/4× 模型来源 |
| QuickSRNet source reference | [Qualcomm AI Hub Models](https://github.com/qualcomm/ai-hub-models) | 网络结构与移动端参考实现 |
| AndroidX Media3 | [androidx/media](https://github.com/androidx/media) | 播放器、MediaCodec、GL effect 和 Lanczos 基线 |
| ONNX Runtime | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | Android CPU 推理和统一 Java API |
| ONNX Runtime QNN plugin | [onnxruntime/onnxruntime-qnn](https://github.com/onnxruntime/onnxruntime-qnn) | Qualcomm QNN Execution Provider |
| Qualcomm QNN Runtime | [Maven Central metadata](https://central.sonatype.com/artifact/com.qualcomm.qti/qnn-runtime/2.49.0) | HTP/CDSP runtime 二进制依赖 |

模型、训练数据、runtime 和媒体的许可边界并不相同。准确 revision、hash、许可证和再分发限制见 [第三方说明](THIRD_PARTY_NOTICES.md)。

## 主要依赖

| 依赖 | 锁定版本 |
| --- | --- |
| Android Gradle Plugin | `8.13.1` |
| Gradle | `8.14` |
| compileSdk / targetSdk / minSdk | `36 / 35 / 27` |
| ONNX Runtime Android | `1.26.0` |
| ONNX Runtime QNN plugin | `2.5.0` |
| Qualcomm QNN Runtime | `2.49.0` |
| AndroidX Media3 ExoPlayer/UI/Effect | `1.11.0` |
| PC NumPy / ONNX Runtime / Pillow / imageio-ffmpeg | `2.2.6 / 1.22.1 / 12.3.0 / 0.6.0` |
| 模型导出 ONNX / PyTorch | `1.18.0 / 2.11.0` |

Android 依赖从 Google Maven/Maven Central 获取；PC 依赖完整列表位于 `pc-benchmark/requirements*.txt`。

## Source-only 发布边界

提交前运行：

```powershell
.\scripts\verify-publication.ps1
```

允许提交原创源码、测试、模型来源、hash、机器可读计划和脱敏汇总；禁止提交：

- checkpoint、ONNX、APK/AAB/AAR 和 vendor `.so`；
- 私人图片、视频、截图、URI、设备序列号和原始日志；
- QNN trace、raw tensor、签名文件与凭据；
- 未确认可再分发的第三方内容。

扫描通过只代表候选源码未命中已知泄漏规则，不授予模型、数据集、Qualcomm runtime 或 APK 的再分发许可。完整规则见 [发布边界](docs/PUBLICATION_BOUNDARY.md)。

## 已知边界

- 不能声称所有 Qualcomm 手机都能实时运行 720p/1080p/1440p；
- 4K 档不是原生神经 4K；
- MediaCodec Render/Drop 不等于最终屏幕 latch 或完整 A/V sync 证明；
- 当前开放语料不是所有商业日本动漫、字幕、编码和时序缺陷的代表；
- 当前还不是可供其他 App 直接依赖的稳定 AAR。

优化过程见 [实时视频超分经验总结](docs/REALTIME_VIDEO_SR_LESSONS.md)，整体路线见 [播放器路线图](docs/PLAYER_ROADMAP.md)，下一阶段拆分见 [动漫视频实时超分与插帧执行计划](docs/ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)。
