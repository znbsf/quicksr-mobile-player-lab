# QuickSR Mobile Player Lab

[English](README.md) | **简体中文**

面向 Android 的图片与视频超分实验 App。项目采用 PC-first 路线：先在可复现语料上筛选倍率、退化适应性、画质和性能，再把合适的路径放到 Qualcomm QNN HTP/NPU 上进行真实播放验证。

> **当前结果：**主档 `640×360 → 1920×1080` QuickSR QNN 路径已在一台 Qualcomm 物理设备上达到 **30.0045 FPS 平均处理吞吐**，effect 层 drop/bypass 为 0；但 SurfaceFlinger 最终显示两轮仅 **1/2 PASS**，因此目前仍不能声称“保证原片帧率”。

当前版本：**v0.15.0**。这是研究原型，不是生产播放器，也不是可直接依赖的稳定 AAR。

## 一分钟了解项目

- 图片与视频超分，覆盖 QuickSRNetSmall 1.5×、2×、3×、4× 模型流程；
- Media3/MediaCodec 播放，支持原画、GPU Lanczos、GPU-resident Anime4K x2 Small、QuickSR CPU 和 QuickSR QNN HTP；
- 固定 shape QNN 会话、有界队列、持久化 Tensor、frame/PTS 所有权和分阶段结构化遥测；
- 默认关闭的动漫 cadence 复用实验：保留每个源 PTS，不插帧，不固定“隔三帧算一次”；
- 18 条 PC 路线、72 个权利清晰案例的画质评测；
- RIFE、IFRNet 插帧候选的离线/CLI 移动探针；尚未接入播放器。

## 当前状态

| 门禁 | 状态 | 证据边界 |
| --- | --- | --- |
| 主机构建与检查 | **PASS** | 使用本地合法依赖后，单测、lint、debug APK 和 Android test APK 已通过。 |
| 物理机 QNN 功能 | **DEVICE-BOUNDED PASS** | 已确认严格 HTP 配置并拒绝 CPU EP 静默回退；未证明逐节点 placement。 |
| 1080p 处理吞吐 | **PASS** | 828 个稳态帧、30.0045 FPS、源 PTS 覆盖约 1、effect drop/bypass 0/0。 |
| 1080p 最终显示节奏 | **OPEN / FAIL** | 原画 SurfaceFlinger 2/2 PASS；QuickSR QNN 1/2 PASS。 |
| A/V sync | **OPEN** | 冻结的 30 FPS 验证片源没有音轨。 |
| thermal / power | **OPEN** | 当前默认架构尚无 10～30 分钟正式验证。 |
| 代表性动漫画质 | **OPEN** | 已有 PC 夹具；手机同源同帧盲审待执行。 |
| Anime4K x2 Small | **IMPLEMENTED / DEVICE FUNCTION** | 已有单设备功能证据；画质、GPU timing 和跨设备仍待验证。 |
| 插帧 | **OFFLINE ONLY** | 已有候选 CLI 探针；没有播放器、A/V sync 或 thermal 证据。 |

最终 SurfaceFlinger A/B/B/A 对照：

| 轮次 | 路径 | 结果 | actual-present FPS | 长 / 短异常 |
| --- | --- | --- | ---: | ---: |
| A1 | 原画 | PASS | 29.9792 | 0 / 0 |
| B1 | QuickSR QNN | FAIL | 30.0341 | 1 / 2 |
| B2 | QuickSR QNN | PASS | 30.0030 | 0 / 0 |
| A2 | 原画 | PASS | 29.9544 | 0 / 0 |

失败的 QNN 轮次平均仍为 30 FPS，但包含一个 58.153 ms 长间隔和补偿短间隔。因此，“平均吞吐达到原片帧率”和“最终显示稳定”是两个不同门禁。

## 1080p 输入输出链路

```text
Media3 / MediaCodec SDR texture（保留源 PTS）
  -> 640x360 RGBA8 readback
  -> float32 NCHW [1, 3, 360, 640] 输入（约 2.64 MiB）
  -> QNN HTP 上的 QuickSRNetSmall 3x
  -> 两个 pinned float32 NHWC [1, 1080, 1920, 3] 输出（各约 23.73 MiB）
  -> deferred copy + 四条带 NHWC-to-RGBA8 pack
  -> 直接上传至 1920x1080 Media3 output texture
  -> SurfaceFlinger，沿用原始 PTS
```

流水线并行处理第 N+1 帧推理、第 N 帧后处理和第 N−1 帧 GL 交付。frame admission 上限保持 2，不用无界排队制造表面吞吐。

### 视频档位

| 档位 | 神经路径 | 定位 |
| --- | --- | --- |
| 720p | `640×360 → 1280×720`，2× | 性能归因与回归诊断档 |
| 1080p | `640×360 → 1920×1080`，3× | 手机产品第一硬门 |
| 1440p | `640×360 → 2560×1440`，4× | 高分实验档 |
| 4K 显示 | `640×360 → 神经 1920×1080 → GPU 3840×2160` | 显示保底，**不是**原生神经 4K |

图片路径执行完整图片 2×，支持 CPU/QNN 和按内存上限分块；它与视频固定 shape 热路径不是同一个性能合同。

## 阶段学习报告

项目从“证明 QNN 能执行”开始，经历了低于 20 FPS 的实现，推进到目前 30 FPS 的平均处理吞吐。以下数字都只对应已记录的设备、APK、模型、片源和统计口径。

### 已保留在默认路径的优化

| 优化 | 保留原因 |
| --- | --- |
| 固定 shape、hash、持久化 session 与 tensor | 固定模型/runtime 身份，消除逐帧初始化。 |
| 严格 QNN HTP 配置 | fail closed，不把 CPU 静默回退冒充 NPU 证据。 |
| 三段有界流水线 | 阶段观测由串行约 11.435 FPS 提升到早期 overlap 17.960 FPS；后续优化补足剩余缺口。 |
| float32 NHWC 输出与四条带 pack | RGB 连续访问并固定行所有权；两条带真机更慢。 |
| 双 pinned ORT output 与 deferred copy | 同机 A/B/B/A 中 inference caller p95 平均由 34.733 降至 30.529 ms，代价约 23.73 MiB。 |
| 只在首个输出执行完整 finite scan | 移除稳态每约 120 帧一次的 330～353 ms 诊断停顿，同时保留会话 fail-closed。 |
| 同尺寸 Media3 output texture 直传 | 删除中间纹理和 scale blit；一次观测中 GL submit p95 由 6.435 降至 3.460 ms。 |

最终默认 1080p 路径的稳态数据：

| 指标 | 结果 |
| --- | ---: |
| measured frames | 828 |
| effect output-submit throughput | 30.0045 FPS |
| QNN caller p50 / p95 | 27.884 / 30.736 ms |
| ORT run p95 | 30.244 ms |
| NHWC→RGBA pack p50 / p95 | 11.321 / 15.503 ms |
| GL upload submit proxy p95 | 6.987 ms |
| accepted→output-submit proxy p95 | 179.024 ms |
| effect drop / bypass | 0 / 0 |
| 最大有界队列深度 | 2 |

`179.024 ms` 是多帧流水线延迟，不是单帧服务时间；流水线填满后仍可以按测得的 30 FPS 交付。

### 未默认启用或已经停止的尝试

| 尝试 | 裁决 |
| --- | --- |
| JNI/arm64 NEON output packer | 停止：早期 A/B/B/A 中 pack p50 由 36.566 增至 102.353 ms，平均 FPS 下降 42.94%。 |
| direct FloatBuffer→native pack | 停止：约 116.5 ms；少一次 copy 没有抵消访问和 JNI 成本。 |
| Java direct buffer 逐元素热循环 | 停止：主机/模拟器探针慢于 heap 顺序访问。 |
| 两条带 pack | 停止：真机慢于四条带。 |
| 扩大队列或同一 QNN session 多 worker | 否决：只增加延迟或所有权风险，不提高 graph 服务率。 |
| temporal batch=2 | 未接播放器：主机方向收益约 4.2%～16.9%，但增加一帧等待和更大输出。 |
| spatial batch | 未接播放器：主机结果约 −2.3%～+0.7%。 |
| 双 PBO upload | 仅保留研究开关：GL submit p95 降至 1.832 ms，但显示仍为 1/2 PASS，另增约 15.82 MiB。 |
| 动漫 cadence 复用 | 仅 benchmark：冻结 720p 映射减少 37.79% 推理，但字幕、运动、切镜安全性未验证。 |

最重要的结论是：局部计时变快，不代表最终消费者变好。下一份有价值的实验必须把一个失败 frameId/PTS 与 Perfetto sched/freq、GL fence、BufferQueue 和 SurfaceFlinger FrameTimeline 对齐；归因后只改真正导致 missed latch 的环节。

## 画质与模型方向

在 72 个权利清晰 PC 案例中，QuickSR 在干净输入上 PSNR 胜 30/36，在模糊加 JPEG Q35 输入上仅胜 7/36，在方形漫画上胜 16/18。现有结果说明 QuickSRNetSmall 更适合干净线稿和漫画；严重模糊或压缩素材可能更适合 Lanczos 回退或针对退化训练的模型。

这些案例不能代表全部商业动漫、字幕、颗粒、编码或设备。帧率门关闭后，还必须做手机同源同帧盲审。若 QuickSR 没有稳定、明显的视觉优势，下一条模型路线应是 GPU-resident Anime4K 或 SESR-M5 一类更轻的动漫模型，而不是同时更换模型和架构。

RIFE、IFRNet、ANVIL 一类插帧研究继续后置。插帧会增加推理负担，不能替代“超分首先保持原片帧率”这一要求。

## 构建与运行

前置条件：

- Windows PowerShell、Android Studio 或 Android SDK、Java 17+；
- 本地合法取得且 SHA-256 与 manifest 一致的 QuickSRNet 模型；
- 打包 Qualcomm runtime 二进制前，独立审阅其许可条款。

模型权重和 fixed-shape 二进制不会提交到 Git。先按 [模型准备说明](models/README.md) 准备本地资产；缺失或 hash 不匹配时构建会 fail closed。

构建 arm64/QNN debug APK：

```powershell
.\build-local.ps1
```

也可以指定本地 canonical 2× ONNX：

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

App 中先选择输出档位和后端，再选择本地非 DRM SDR 视频。720p 只作诊断，1080p 是当前主门；1440p 与 4K 显示档需要额外观察内存和温度。

完整 PC-first 语料和真机 QNN 分辨率矩阵命令分别见 [PC benchmark 说明](pc-benchmark/README.md) 和 [Android 移动子集验证](docs/ANDROID_MOBILE_SUBSET_VALIDATION.md)。

## 证据与文档导航

- [当前项目状态](docs/STATUS.md)
- [实现计划与进展](docs/IMPLEMENTATION_PLAN_AND_PROGRESS.md)
- [实时架构优化审计](docs/REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md)
- [1080p 物理机机器可读摘要](docs/evidence/realtime-1080p-physical-20260905.json)
- [Anime4K Android GPU 集成](docs/ANIME4K_ANDROID_GPU_INTEGRATION.md)
- [动漫 cadence 复用合同](docs/ANIME_CADENCE_REUSE.md)
- [动漫超分与插帧研究计划](docs/ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)
- [PC benchmark 说明](pc-benchmark/README.md)
- [物理机 QNN 验证](docs/ANDROID_MOBILE_SUBSET_VALIDATION.md)

## 平台范围

| 环境 | 当前范围 |
| --- | --- |
| Android 8.1+、arm64-v8a、兼容 Qualcomm HTP/CDSP | 目标 QNN 平台；当前证据仅限一台物理设备。 |
| Android 8.1+、其他 arm64-v8a 设备 | 可构建 CPU/GPU 路径；尚无多厂商兼容矩阵。 |
| Android Studio API 35 x86_64 模拟器 | 仅用于 CPU/GPU 功能检查，绝不计作 QNN HTP 性能证据。 |
| armeabi-v7a、32 位 x86、iOS | 不支持。 |

当前媒体范围是本地、SDR、非 DRM 视频。HDR、DRM、直播、复杂字幕链路、跨厂商表现和长时热稳定尚未验证。

## 上游与锁定依赖

| 组件 | 版本 / 上游 | 用途 |
| --- | --- | --- |
| QuickSRNetSmall | [Qualcomm AIMET Model Zoo](https://github.com/quic/aimet-model-zoo/tree/develop/aimet_zoo_torch/quicksrnet) | 1.5×/2×/3×/4× checkpoints |
| QuickSRNet 参考实现 | [Qualcomm AI Hub Models](https://github.com/qualcomm/ai-hub-models) | 网络结构与移动端参考 |
| AndroidX Media3 | `1.11.0` / [androidx/media](https://github.com/androidx/media) | 播放器、MediaCodec、GL effect、Lanczos 基线 |
| ONNX Runtime Android | `1.26.0` / [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | CPU 推理和统一 Java API |
| ONNX Runtime QNN plugin | `2.5.0` / [onnxruntime-qnn](https://github.com/microsoft/onnxruntime/tree/main/onnxruntime/core/providers/qnn) | QNN Execution Provider |
| Qualcomm QNN Runtime | `2.49.0` / [Maven metadata](https://central.sonatype.com/artifact/com.qualcomm.qti/qnn-runtime/2.49.0) | HTP/CDSP runtime 依赖 |
| Android Gradle Plugin / Gradle | `8.13.1` / `8.14` | Android 构建 |
| compileSdk / targetSdk / minSdk | `36 / 35 / 27` | Android 平台级别 |

准确 revision、hash、notice 和再分发边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## Source-only 发布边界

发布前运行：

```powershell
.\scripts\verify-publication.ps1
```

仓库可提交原创源码、测试、来源说明、hash、机器可读计划和去标识摘要；不得提交模型 checkpoint、ONNX、APK/AAB/AAR、vendor 库、私人媒体、设备标识、原始日志/trace/tensor、签名材料或凭据。

源码中唯一保留的神经 shader 是单独审查的 Anime4K x2 Small 文本，已用上游 commit、bytes 和 SHA-256 固定，并保留 MIT notice。扫描通过不授予模型、数据集、Qualcomm runtime 或 App 包的再分发权。完整规则见 [发布边界](docs/PUBLICATION_BOUNDARY.md)。

## 已知边界

- 现有结果不能证明所有 Qualcomm 手机都能实时运行；
- 4K 档是神经 1080p 后由 GPU 放大，不是原生神经 4K；
- MediaCodec render/drop 与 App output-submit 指标不能证明最终屏幕 latch 或端到端 A/V sync；
- 当前语料不能代表所有动漫风格、字幕、退化、编码和 cadence 缺陷；
- 项目尚未拆成稳定库。

整体方向见 [播放器路线图](docs/PLAYER_ROADMAP.md)，更完整的优化记录见 [实时视频超分经验总结](docs/REALTIME_VIDEO_SR_LESSONS.md)。
