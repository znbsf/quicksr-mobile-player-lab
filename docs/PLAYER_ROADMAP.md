# QuickSR Mobile Player 路线图

> 文档更新：2026-09-01
>
> 文档性质：阶段边界、技术合同与证据门禁；不是完成证明。
>
> 当前活动状态：Media3 播放器与逐帧 QNN HTP 闭环已实现，并完成单 workload 吞吐观察。M1/M2 的主要功能代码已经存在；M3 有 `640×360 → 1280×720` 完整帧实验路径，但 correctness、画质、p95/p99、A/V sync、最终显示 latch、持续 thermal 和 tile/stitch 门禁尚未全部关闭。M4 AAR 仍未实现。

## 1. 先把当前事实说清楚

本项目正在验证：QuickSRNetSmall ×2 能否在 Android 播放链路中，经 Qualcomm QNN HTP/NPU 对视频画面进行可复现、可评价、可持续的超分，并最终封装成可由其他 Media3 App 接入的 effect library。

当前实现和正式门禁必须分开：

| 阶段 | 当前实现快照 | 尚未关闭 |
| --- | --- | --- |
| 既有 runtime 探针 | 固定 `64×64 → 128×128` 的 QNN HTP execution 已有独立证据 | 原冻结 PC golden correctness 仍为 FAIL |
| M0 图片路径 | 已实现系统选图、完整图片 tile 2×、CPU/QNN、预览和 PNG 保存 | 本轮没有新的权利清晰质量报告 |
| M1 | Media3 播放器、effect、PTS 传递、原始/GPU 模式已经运行 | 正式 seek/flush/EOS、截图 hash 和完整生命周期合同 |
| M2 | 完整帧进入 CPU/QNN，静态 shape 与分阶段计时已经实现 | 冻结 correctness、颜色等价和同帧 reference |
| M3 experimental | 默认 `640×360 → 1280×720`；指定 23.976 fps workload 的播放器代理约 24 fps、Drop=0 | 画质、最终 latch、A/V sync、p95/p99、功耗、正式 thermal 和 tile/stitch |
| M4 | 未实现 | AAR、模块拆分、稳定 API、兼容矩阵和全新 checkout 交付验证 |

因此可以声称“可运行的实验播放器和逐帧神经路径已实现”，但仍不能泛化为“720p 在所有设备实时且画质正确”。

## 2. 产品边界

### 2.1 首个可交付产品

首个播放器产品不是通用的 mpv/VLC 动态插件，而是：

1. 一个只播放本地、非 DRM 测试视频的最小 Media3 App；
2. 一个编译期接入的 `quicksr-media3-effect` Android library；
3. 一个复用现有 QNN HTP strict runtime 的 `quicksr-runtime-qnn` 模块；
4. 一套能分别证明画质、硬件执行、全链路延迟、丢帧、温度和构建身份的证据工具。

最终接入形态预计类似：

```kotlin
player.setVideoEffects(
    listOf(
        QuickSrEffect(
            scale = 2,
            backend = QNN_HTP_STRICT,
            qualityMode = REALTIME
        )
    )
)
```

这是一种供采用相同 Media3 版本的 App 在构建时接入的 AAR，不是能安装到任意现成播放器里的系统级插件。

### 2.2 首版主动排除

- DRM / secure content；
- HDR；
- 网络直播、弹幕、复杂字幕和投屏；
- 540p/1080p 输入的实时承诺；
- 视频插帧、降噪、去模糊等额外模型；
- 未验证许可的视频、模型或 vendor 二进制进入公开仓库；
- 用单个 `64×64` kernel timing 推导整帧 FPS。

Media3 官方明确说明 `setVideoEffects` 当前只支持默认 `MediaCodecVideoRenderer`、不支持 DRM 内容，且必须至少在 `prepare()` 前调用一次。首版按这个公开合同设计，不扩张范围。

## 3. M0：真实静态图片 ROI

### 3.1 M0 的固定数据流

```text
真实图片（仅本地读取）
  → EXIF 方向归一化
  → 确定性 128×128 HR ROI
  → 固定 2× downsample，得到 64×64 LR
  ├─→ bilinear 2×，得到 128×128 baseline
  └─→ RGB / NCHW / normalization
       → ORT + QNN HTP strict
       → 128×128 float output
       → clamp / RGB8
  → 与同一 HR ROI 比较
```

M0 必须保存或引用：

- 输入图片的公开来源或本地私有来源标记；
- 原图 bytes hash、解码后像素 hash、尺寸、EXIF 处理结果；
- ROI 坐标、crop 策略和 ROI pixel hash；
- downsample 算法、颜色空间、取整规则；
- `reference-hr-128.png`、`input-lr-64.png`、`baseline-bilinear-128.png`、`qnn-htp-128.png`；
- model hash、execution plan hash、APK/source/build identity；
- QNN device/backend、CPU fallback 状态、ORT placement 与 HTP trace；
- bilinear 与 QNN 对 HR 的相同口径指标；
- 人工 A/B 观察和失败点。

私人照片不得上传到公开仓库。公开案例必须使用许可清楚的 fixture；私人运行只提交脱敏 receipt 和统计，receipt 不保存本机绝对路径或原始 URI。

### 3.2 M0 通过条件

M0 只有在以下条件全部满足时才可标为完成：

1. 同一份冻结 workload 可从原图确定性复算到四张图片；
2. QNN HTP execution、无 CPU fallback、输出图片与 build identity 均有可回读证据；
3. 非有限值、shape、clamp、RGB/NCHW 布局和 PNG 编码经过独立校验；
4. bilinear 与 QNN 使用完全相同的 HR reference 和评价口径；
5. 运行前冻结的 HTP 数值/画质合同已在未参与定阈值的 ROI 上复测；
6. 人工 reviewer 打开四张图片和 receipt 后单独确认。

未通过时应报告为：`M0 implementation present / machine gate pending or failed / human review pending`，而不是“图片超分完成”。

## 4. 为什么播放器首选 Media3 1.11.0

Media3 1.11.0 是 2026-08-05 发布的稳定版。ExoPlayer 的 `setVideoEffects` 可以把 effect 应用到每一帧；`GlEffect` 能创建自定义 `GlShaderProgram`；`GlShaderProgram.queueInputFrame` 接收 `GlTextureInfo` 与 `presentationTimeUs`，并允许异步产生输出 texture。

这条路径的优势是无需 fork 播放器，同时保留 MediaCodec 解码、时间轴、seek、Surface 生命周期和播放器 UI。代价是 `GlEffect` / `GlShaderProgram` 仍标注 `UnstableApi`，因此项目必须锁定 Media3 版本，并把兼容升级当成显式工作，而不是默认二进制兼容。

播放器 effect 应实现自定义异步 `GlShaderProgram`，不应把耗时 NPU inference 直接塞进同步 GL draw 回调：

```text
MediaCodec decoder
  → Surface / Media3 frame processor
  → GL 2D input texture + PTS
  → staging/readback
  → QNN worker
  → output upload
  → Media3 output texture + 原 PTS
  → SurfaceView
```

## 5. GL texture 与 NPU 之间没有现成的 Java 零拷贝

标准 ORT Java `OnnxTensor` 的公开输入是 `ByteBuffer`、`FloatBuffer` 等 NIO buffer；Media3 交给 effect 的则是 OpenGL texture。基于这两个官方 API，可以确定首版至少存在两个必须测量的边界：

```text
Media3 GL texture
  → [A: GL readback，GPU memory → system memory]
  → DirectBuffer / RGB layout conversion
  → [B: ORT/QNN input transfer]
  → HTP inference
  → [C: ORT/QNN output transfer]
  → DirectBuffer
  → [D: GL upload，system memory → GPU memory]
  → output texture
```

QNN 的 `enable_htp_shared_memory_allocator=1` 可能减少 B/C 中的部分搬运，但官方文档没有说明它能把 OpenGL texture 直接绑定为 ORT Java tensor。因此在真实 trace 证明前：

- 不得写“GPU/NPU zero-copy”；
- 不得把 HTP kernel timing 当成播放器端到端 timing；
- A、预处理、B/C、后处理、D 必须分别计时；
- 每帧 bytes、buffer 分配次数和峰值 in-flight memory 必须记录。

优化顺序应以测量为依据。候选手段包括复用 direct buffer、ORT pinned outputs、双/三 PBO、GL fence、QNN shared allocator、context cache、uint8/NHWC 或 W8A8 I/O；任何一个都不能在 benchmark 前写成已经减少了拷贝。

## 6. 颜色空间合同

颜色合同取决于 `DefaultVideoFrameProcessor` 的 working-color 配置，不能只从
`GlEffect.toGlShaderProgram(context, useHdr)` 的旧式概括推断。Media3 1.11.0
实际默认 `WORKING_COLOR_SPACE_DEFAULT`：SDR/SRGB 中间 effect 保留输入的非线性
电信号 RGB；只有显式 `WORKING_COLOR_SPACE_LINEAR` 才执行 SDR EOTF，而且视频
SDR 使用 SMPTE 170M，不是 sRGB。当前 App 显式固定默认模式。QuickSR 的图片
预处理仍需按模型实现查证 RGB/range/transfer，不能把任何 GL 值无证据地当成
PNG sRGB byte 或 linear RGB。

首版只支持 SDR，并在运行前冻结以下合同：

```text
Media3 default-working-color nonlinear SDR texture
  → 明确的 transfer function / quantization
  → 模型要求的 RGB8 或 float RGB
  → NCHW/NHWC layout
  → QuickSR output
  → 逆 transfer function
  → Media3 default-working-color nonlinear SDR output texture
```

必须验证：

- M0 PNG 路径和播放器 texture 路径送入模型前的同像素等价性；
- limited/full range、clamp、rounding、RGB channel order；
- 透明通道不参与模型且输出 alpha 规则固定；
- 色条、灰阶、边缘和真实动画帧的 round-trip；
- HDR 输入应 fail closed 或明确关闭 effect，而不是按 SDR 静默处理。

颜色空间未通过时，即使 HTP trace PASS，也只能声称“模型执行”，不能声称“播放器画质正确”。

## 7. 背压、PTS 与资源所有权

`GlShaderProgram` 可以异步输出，但实现必须遵守 texture 所有权和输入容量通知。播放器链路不能靠无限队列掩盖 NPU 跟不上视频帧率。

首版背压合同：

1. `maxInFlightFrames` 在 plan 中冻结，初始值建议 1～2，不能运行后偷偷扩大；
2. 在通知 `onInputFrameProcessed` 前，输入 texture 必须已完成 readback 或复制到项目自有 staging resource；
3. 只有真正有容量时才通知 `onReadyToAcceptInputFrame`；
4. 输出必须保持 PTS 单调，不能把旧 NPU 结果贴到新帧；
5. seek/flush 产生新的 generation，旧 generation 完成的 inference 只能丢弃，不能输出；
6. EOS 前必须处理或按冻结策略登记所有已接收帧；
7. 慢帧策略必须事先选择：阻塞、显式 bypass 或显式 drop，不能静默复用上一帧；
8. 每次运行保存 queue depth、accepted、processed、bypassed、dropped、stale-after-flush、late-frame 和最大等待时间。

“播放看起来没卡”不是背压证据。A/V sync、PTS、drop/bypass 统计和持续运行记录必须同时存在。

## 8. M1～M4 执行路线

### M1：Media3 播放器骨架与 texture 生命周期（主要功能已实现，正式门禁未全关）

范围：

- 锁定 Media3 `1.11.0`；
- 只播放一个冻结的本地、非 DRM、SDR H.264 MP4；
- 建立 `OFF / GL passthrough` 两档；
- 自定义 `GlEffect` / `GlShaderProgram` 能接收 texture、PTS，输出同尺寸 texture；
- seek、pause/resume、flush、EOS 不泄漏 texture，不输出陈旧 PTS；
- 记录 decode/render/drop 基线。

M1 不调用 QuickSR 也可以通过。它证明播放器与 effect 生命周期正确，不证明 NPU。

M1 门禁：

- workload、Media3 版本、APK/source/build identity 已冻结；
- passthrough frame hash/截图与无 effect 基线在冻结容差内；
- PTS 单调，seek 后没有旧 generation 输出；
- 60 秒基线播放完成，drop 和异常有明确记录；
- 人工完成播放、seek、暂停和旋转/生命周期检查。

### M2：低分辨率完整帧 QNN 闭环（主要功能已实现，正确性门禁待补）

范围：

- 在 Media3 texture 与现有 QNN HTP strict runtime 之间接入明确的 readback/upload；
- 先使用 `128×128` 或另一事前冻结的小型完整视频帧，不把 ROI 伪装成 full-frame；
- 增加 `OFF / bilinear / QNN HTP` 三档；
- 保存同一 PTS 的输入、bilinear、QNN 输出和离线 reference；
- 逐段测量 readback、preprocess、HTP、postprocess、upload 和 end-to-end。

M2 门禁：

- strict QNN HTP、CPU fallback disabled、ORT placement、QNN trace 分别 PASS；
- 播放器同帧输出与 M0/主机 reference 使用冻结合同比较；
- 颜色空间 round-trip PASS；
- 所有已接受帧都能由 PTS 和 artifact identity 追溯；
- 允许慢速或低 FPS，但必须如实记录；不能声称实时。

### M3：360p → 720p tile/full-frame 与持续播放（完整帧性能候选已观察）

范围：

- 冻结一个版权安全的 720p reference clip；
- 用确定性脚本生成 360p degraded input；
- 固定 tile size、overlap、padding、crop 与 stitch 规则；
- 处理画面边界和非 tile 整数倍尺寸；
- 比较 bilinear、QNN 和 HR reference；
- 引入有证据的 buffer/PBO/pinned-I/O 优化；
- 做持续播放、A/V sync、温度和功耗观察。

M3 门禁：

- tile 数、每 tile identity、拼接顺序、边界裁剪可独立复算；
- seam、边缘、字幕线条、静态画质和时间稳定性分别评价；
- PSNR/SSIM 等机器指标与人工 A/B 独立保存，不能互相替代；
- p50/p95/p99 分阶段延迟、late/drop/bypass、queue depth、内存和温度均有证据；
- 运行前冻结目标帧率及其 frame budget；只有 p95 end-to-end、持续时长和 drop/thermal 门限都通过后，才允许在该分辨率/设备/模式下写“实时”。

### M4：可复用 AAR 与公开交付（未实现）

范围：

- 将播放器壳、Media3 effect、QNN runtime 和 evidence 工具拆成明确模块；
- 输出 `quicksr-media3-effect` AAR 和最小 demo App；
- 锁定并记录 Media3/QNN/ORT/QAIRT 兼容矩阵；
- 增加无 Qualcomm NPU、QNN 初始化失败、HDR/DRM 等 fail-closed 行为；
- 完成至少一台 Xiaomi 13 Ultra 的安装、播放、证据导出与回读；
- 对公开仓库执行许可、模型来源、隐私与大文件审计。

M4 门禁：

- 一个全新 checkout 能按文档构建；
- demo 通过明确 API 使用 AAR，而不是引用 app 内部类；
- 模型、runtime、测试视频和报告都有来源、许可和 hash；
- 不提交私人视频、绝对路径、设备标识、vendor secret 或未确认可再分发的 `.so`；
- README 的每一项能力声明都能指向 machine evidence；
- human reviewer 独立核查真实引用、视频效果和门禁后，才可标记 reviewed。

## 9. 所有阶段共用的证据轴

以下轴必须分别保存，不能用一个绿色按钮合并：

| 证据轴 | 需要回答的问题 |
| --- | --- |
| Workload / provenance | 到底运行了哪张图、哪段视频、哪一个 crop/tile、哪套模型和参数？ |
| Build linkage | 源码、APK/AAR、模型、plan 和真机 receipt 是否互相绑定？ |
| Runtime | ORT/QNN 是否初始化并完成预期次数？ |
| Placement | 哪些节点属于 QNN EP；是否出现 CPU EP compute？ |
| Hardware | 是否有 HTP architecture、RPC、accelerator cycles/HVX 等底层证据？ |
| Correctness | 输出是否满足运行前冻结的数值合同？ |
| Visual quality | 相对 HR 与 bilinear 是否有可复核的提升或退化？ |
| Pipeline performance | readback、conversion、inference、upload 和 end-to-end 各占多少？ |
| Playback behavior | PTS、A/V sync、late/drop/bypass、seek/flush 是否正确？ |
| Sustained behavior | 温度、功耗、频率、内存和长时间退化如何？ |
| Human review | reviewer 是否真实打开引用、图片/视频和运行证据？ |

机器测试不能替代 human review；HTP execution PASS 不能替代 correctness；正确性 PASS 不能替代画质提升；kernel benchmark 不能替代播放器 end-to-end；一次短跑不能替代持续性能。

## 10. 允许与禁止的阶段性声明

| 条件 | 允许声明 | 禁止声明 |
| --- | --- | --- |
| 仅 M0 | “真实图片固定 ROI 已进入图片评价闭环” | “完成全图/视频超分” |
| M1 PASS | “Media3 effect texture 生命周期已跑通” | “NPU 播放器已完成” |
| M2 HTP PASS、质量未过 | “低分辨率完整帧真实进入 HTP” | “画质正确/实时” |
| M3 质量 PASS、性能未过 | “360p→720p 画质合同通过” | “实时播放” |
| M3 全部门禁 PASS | “在指定设备、视频、模式、帧率和持续时长下通过实时门限” | “所有手机/所有视频实时” |
| M4 + human review | “可复用 Media3 AAR 和 demo 已审计交付” | “任意播放器可动态安装的通用插件” |

## 11. 官方来源

1. [Media3 1.11.0 release notes（2026-08-05）](https://github.com/androidx/media/blob/release/RELEASENOTES.md#1110-2026-08-05)
2. [ExoPlayer `setVideoEffects` API 与限制](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java)
3. [Android `GlEffect` API：SDR/HDR texture 色彩语义](https://developer.android.com/reference/androidx/media3/effect/GlEffect)
4. [Android `GlShaderProgram` API：texture、PTS、异步输出与 GL context](https://developer.android.com/reference/androidx/media3/effect/GlShaderProgram)
5. [ORT Java `OnnxTensor` API：NIO buffer 输入和 backing buffer](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OnnxTensor.html)
6. [ORT Java `OrtSession`：pinned outputs](https://github.com/microsoft/onnxruntime/blob/main/java/src/main/java/ai/onnxruntime/OrtSession.java)
7. [QNN Execution Provider：HTP、CPU fallback、shared allocator 与 profiling 选项](https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md)
8. [Qualcomm AI Hub QuickSRNetSmall 模型页](https://aihub.qualcomm.com/models/quicksrnetsmall)
9. [Qualcomm QuickSRNetSmall 模型实现：2×/3×/4× scale factor checkpoint 来源](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/quicksrnetsmall/model.py)

## 12. 下一次实际执行入口

1. 冻结视频 correctness/画质合同，并用同 PTS CPU/golden 与权利清晰 reference 验证；
2. 保存各阶段 raw ns 样本、队列深度和 accepted/processed/drop/late，报告 p50/p95/p99；
3. 补 SurfaceFlinger 或等价最终显示观测、A/V sync、seek/flush/pause/resume；
4. 优先 A/B 优化当前约 10 ms 的输出转换与 GL upload；
5. 只有在测量显示 Java 热循环主导时才引入 NDK NEON 或 compute shader；
6. 再评估 W8A8 I/O、QNN shared allocator、native I/O 或更深 zero-copy；
7. 通过持续 thermal/功耗与画质门禁后，再拆分可复用 AAR。

若 GL readback/upload 主导总延迟，应保留负结果，并据此决定继续优化 Media3 effect 还是另立 native pipeline 实验；不得在没有测量前用“未来 zero-copy”跳过当前证据。
