# 动漫视频实时超分与插帧：研究结论及执行拆分

日期：2026-09-03

文档性质：技术决策记录、任务依赖和统一验收合同；不是实时、画质、热稳定或产品完成证明。

## 1. 决策摘要

当前 1080p 离线吞吐的主要矛盾不是硬件视频解码，而是以下跨域链路仍被单个 worker 串行执行：

```text
Media3 GL texture
  -> PBO/readback 与 CPU 可访问映射
  -> RGBA 输入复制与 RGB/NCHW 转换
  -> ORT/QNN HTP inference
  -> float NCHW 输出转换与 direct-buffer 复制
  -> GL texture upload
  -> Media3 output 与最终显示
```

2026-09-02 的无抓取真机矩阵观察到：

| 档位 | 功能门禁 | 中位 observed FPS | queue / ORT / output conversion / total p50 |
| --- | --- | ---: | --- |
| `640×360 -> 1280×720` | 11/11 PASS | 23.71 | 95 / 10 / 23 / 129 ms |
| `640×360 -> 1920×1080` | 11/11 PASS | 11.69 | 329 / 43 / 37 / 414 ms |
| `640×360 -> 2560×1440` | 受门禁功能 PASS | 7.71 | 单案例，不作为实时分布 |
| `640×360 -> neural 1080p -> display 4K` | 显示回退功能 PASS | 11.36 | 不是原生神经 4K |

这些结果均归类为 `offline`。它们不包含完整 SurfaceFlinger latch、全量 A/V sync、持续 thermal、功耗或通用设备证明。

1080p 的中位 queue 与 total 相减只能作为近似，但 `414 - 329 ~= 85 ms` 与约 11.8 FPS 的串行服务容量一致；其中 ORT/QNN 约 43 ms、输出转换约 37 ms。24 FPS 和 30 FPS 的预算分别只有 41.67 ms 和 33.33 ms。因此：

1. 缩短队列只会降低积压延迟，除非同时采用 drop、bypass 或结果复用，否则不会增加吞吐；
2. 第一优先级是补齐端到端观测，并让 inference 与 postprocess 可以受控重叠；
3. 第二优先级是用 native/NEON、布局变化或 GPU shader 减少输出转换和跨域搬运；
4. 动漫停格感知可以减少实际需要运行 SR 的帧数，但必须按内容自适应，不能固定每三帧取一次；
5. 真正的时序动漫 VSR 与插帧先作为研究/离线评测轨，不与实时热路径首轮修改混在一起。

## 2. 已确认、推断与开放门禁

### 2.1 已确认

- 一个 arm64 Qualcomm 设备上的 720p/1080p 主矩阵功能均为 11/11 PASS；1440p 与 4K 显示回退通过受门禁功能检查。
- 27 个合同选帧的 Android QNN 输出与 PC CPU ORT 张量对照为零失配、零非有限值。
- Media3/MediaCodec 硬件解码路径已经工作；现有证据不支持把重写 decoder/player 作为第一优化项。
- 当前 effect 使用单线程 executor，QNN inference、输出打包和复制在同一 worker 上串行发生。
- 当前 effect 的 `totalProcessingMs` 从 processor 收到图像后开始，到 worker 产出 RGBA buffer 时结束；Media3 内部较早的 GPU readback/map 和较后的 GL upload/最终显示不在同一个端到端时钟内。
- `qnn.perf_mode=sustained_high_performance` 已启用；再次打开同名模式不是新的优化。

### 2.2 有证据支持、但仍需 A/B 的推断

- 1080p 排队约 329 ms 是服务能力不足后的症状，约 85 ms 的串行服务时间才是吞吐上限的直接解释。
- 将 QNN frame N+1 与 frame N 的输出打包放入有界双阶段流水线，理想稳态可能由 `43 + 37 ms` 靠近 `max(43, 37) ms`；这只是设计上限，不是预计真机结果。
- native/NEON 直接写入可上传 direct buffer，或改用更适合显示的模型输出布局，可能比继续扩大 executor 队列更有效。
- GPU 常驻的 Anime4K/FSRCNNX 避免 CPU/NPU 往返，可能在手机实时路径上胜过质量更强但需要大规模内存搬运的模型。

### 2.3 仍然开放

- GPU readback/map、输入复制、GL upload 和最终 latch 的同一时钟端到端分布；
- accepted、processed、late、dropped、bypassed、reused、queue depth 与 PTS-wall-clock drift；
- QNN detailed/optrace、per-node placement 和是否发生隐藏 CPU compute；
- 10～30 分钟 thermal、频率、功耗、内存峰值和 A/V sync；
- 手机同帧视觉质量、代表性动漫线条/字幕/压缩场景、时序闪烁与人工审核；
- 模型源码、权重和数据集的独立许可审计。

## 3. 实时流水线的执行优先级

### P0：先关闭观测缺口

必须先保存同一运行中的 raw nanoseconds，并派生 p50/p95/p99/max：

```text
effect accepted
  -> GPU readback/PBO ready（若公开 API 无法直接测量，必须标为代理或未测）
  -> input copied
  -> worker started / queue wait
  -> preprocess completed
  -> OrtSession.run completed
  -> output pack completed
  -> direct-buffer copy completed
  -> GL upload completed
  -> output submitted
  -> final display/latch proxy
```

每次运行同时保存配置、模型 hash、APK/source identity、输入 clip identity、generation、PTS、队列深度以及 processed/drop/bypass/reuse 计数。无法直接观测的 Media3 内部阶段不得伪装成零成本。

### P1：在同一模型上解决串行吞吐

1. 把 inference 与 output pack 拆成有界两阶段；只允许 2～3 个明确归属的 buffer，禁止无限在途内存；
2. A/B Java packer 与 JNI/NEON direct packer，输出必须逐帧 hash 对照；
3. 将 queue policy 冻结为一种明确策略：阻塞、latest-wins drop、bypass 或 hold-result reuse；
4. seek/flush 使用 generation 隔离，旧 generation 结果不得贴到新 PTS；
5. 不在同一提交里同时改模型、I/O layout、队列策略和 QNN tuning，保证收益可以归因。

1080p 单帧 float NCHW 输出约 24.9 MB，RGBA8 约 8.3 MB。双/三缓冲必须把 ORT、Java/native staging 和 GL 资源全部计入峰值内存，不能只计算逻辑图像大小。

### P2：减少数据量或改变计算域

- 评估 quantized/NHWC/uint8 graph I/O；这需要转换或重训及 correctness 复核，不能只切换 provider option；
- A/B QNN HTP shared-memory allocator，记录依赖、实际生效证据和负结果；它不等同于 GL texture 零复制；
- 保留 720p 神经输出并由 GPU 放大到显示尺寸，作为低延迟档；
- 单独评估 GPU resident Anime4K/FSRCNNX，避免与 QNN 热路径实现混杂；
- context cache 只作为启动优化，不计入稳态吞吐修复。

上游参考：

- [Media3 1.11.0 ByteBufferGlEffect](https://github.com/androidx/media/blob/1.11.0/libraries/effect/src/main/java/androidx/media3/effect/ByteBufferGlEffect.java)
- [Media3 1.11.0 ByteBufferConcurrentEffect](https://github.com/androidx/media/blob/1.11.0/libraries/effect/src/main/java/androidx/media3/effect/ByteBufferConcurrentEffect.java)
- [ONNX Runtime QNN Execution Provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- [mpv temporal shader / frame history discussion](https://github.com/mpv-player/mpv/issues/8137)
- [mpv FSRCNNX redraw/cache performance record](https://github.com/mpv-player/mpv/issues/9479)

## 4. 动漫/视频超分模型分层

| 层级 | 候选 | 时序 | 当前定位 | 进入 Android 前的门禁 |
| --- | --- | --- | --- | --- |
| A | QuickSR 720p + GPU resize | 否 | 已有真机基线；低延迟保底 | 完整显示、thermal、画质 |
| A | Anime4K Small/Medium | 否，GPU shader | 最快的动漫实时替代 A/B | Android GPU timing、功耗、线条质量 |
| A | FSRCNNX small/line-art | 否，GPU shader | 小型线稿候选 | shader 兼容、许可、同帧质量 |
| B | `realesr-animevideov3` | 否；名称含 video 但主要逐帧 SISR | 动漫退化模型、ncnn/Vulkan 候选 | 权重许可、手机内存与持续吞吐 |
| B | Real-CUGAN | 否 | 动漫逐帧修复候选 | 上游成熟度、权重与设备实测 |
| B | SESR | 否 | 移动 INT8/QAT 架构或重训基线 | 动漫域训练、转换正确性 |
| B/C | Mobile RRN、FANI | 是 | 移动时序 VSR 架构参考 | 自然视频域偏差、状态/PTS、QNN 转换 |
| C | AnimeSR、VQD-SR、APISR | AnimeSR/VQD 为时序，APISR 侧重动漫恢复 | 质量上界、训练与评价参考 | PyTorch/CUDA 路径、数据权利、移动重构 |

首轮模型实验只建立可复算的离线 A/B，不直接承诺集成播放器。每个候选必须分别记录源码许可、权重许可、数据集条款、上游 commit/hash、输入输出 layout、参数量、峰值内存、PC/Android runtime 和失败原因。

主要来源：

- [Anime4K](https://github.com/bloc97/Anime4K)
- [FSRCNNX](https://github.com/igv/FSRCNN-TensorFlow/releases)
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)
- [Real-CUGAN](https://github.com/bilibili/ailab/tree/main/Real-CUGAN)
- [SESR](https://github.com/ARM-software/sesr)
- [Mobile RRN](https://github.com/MediaTek-NeuroPilot/mai22-real-time-video-sr)
- [FANI](https://github.com/kyrie2to11/FANI)
- [AnimeSR](https://github.com/TencentARC/AnimeSR)
- [VQD-SR](https://github.com/researchmm/VQD-SR)
- [APISR](https://github.com/Kiteretsu77/APISR)

## 5. 动漫 cadence 感知的稀疏超分

### 5.1 不采用固定“每三帧一次”

一拍一、一拍二、一拍三描述的是画面元素的更新节奏，不是容器帧率或固定全局相位。同一镜头里可能同时存在一拍三的身体、一拍一的嘴型、每帧移动的摄像机、字幕、粒子和 CG。压缩也会让肉眼相同的 hold frame 在像素上不同。

因此每个解码帧仍需经过廉价变化检测，但只有选择出的 anchor frame 执行完整 SR：

```text
decoded frame + PTS
  -> seek/flush/cut generation check
  -> low-resolution luma + edge + blockwise perceptual difference
  -> small-region motion guard + maximum staleness
  -> unchanged: reuse exact previous SR texture at current PTS
  -> changed: run SR and replace anchor texture
```

编码 motion vector 和 residual 可以作为辅助特征，但不能单独代表动漫语义。摄像机平移、命中可靠高对比阈值的字幕变化，以及不同角色采用不同 cadence 时必须 fail toward processing，而不是错误复用；低对比或亚阈值文字不属于当前保护可证明范围。

### 5.2 需要保存的证据

- 每帧 `processed/reused/dropped/bypassed` 决策及阈值版本；
- scene cut、seek、flush 和 generation reset；
- 原始 PTS 单调性、最大 stale age 和 anchor identity；
- 一拍一、一拍二、一拍三、混合 cadence、平移、字幕、口型、粒子、硬切和淡入淡出夹具；
- SR 输出 hash、复用纹理 hash、错误复用率、漏复用率和端到端收益；
- 人工检查节奏是否被改变。

当前 1080p 约 85 ms 串行服务时间若盲目一取三，平均计算量约为 28 ms/源帧；这只是理想算术上界。新画面仍可能等待 85 ms，一拍一场景没有节省，并且当前数字还缺完整传输和 thermal 成本。

相关研究与实现先例：

- [NEMO：anchor frame SR 与 codec 信息传播](https://ina.kaist.ac.kr/projects/nemo/)
- [CIAF：codec motion vector alignment 与 residual-guided skipping](https://arxiv.org/abs/2210.08229)
- [MultiPassDedup：动漫混合 cadence 的社区实现线索](https://github.com/routineLife1/MultiPassDedup)

递归 VSR 模型不能随意跳过状态更新。首个 cadence 原型应先配合逐帧 SISR 或专门设计的 anchor-transfer 系统，而不是直接改造 AnimeSR/Mobile RRN 的递归状态。

## 6. 插帧策略与候选

插帧必须是可选的“流畅模式”，默认保留原作节奏。执行顺序为：

```text
scene-cut detection
  -> hold/duplicate detection
  -> only interpolate between genuine distinct drawings
  -> optional SR/composition
```

不得对 hold frame、seek 边界或硬切盲目插帧。除了 PSNR/SSIM，还要保存 LPIPS、线条 chamfer distance、warp/flicker 指标和人工检查。

| 候选 | 当前定位 | 主要限制 |
| --- | --- | --- |
| RIFE + ncnn Vulkan | 最现实的移动部署原型 | ncnn 端口落后于较新的动漫优化版本；当前设备未验证 |
| IFRNet + ncnn Vulkan | 更紧凑的高效候选 | 以自然视频为主；ncnn 路径仍偏实验性 |
| AnimeInterp | 动漫专用质量基准 | 旧 PyTorch 桌面研究栈，无移动实时证据 |
| EISAI | 动漫线条与评价上界 | GPU/Docker 研究路径，AGPL-3 与数据边界需单独审计 |

来源：

- [RIFE](https://github.com/hzwer/ECCV2022-RIFE) / [rife-ncnn-vulkan](https://github.com/nihui/rife-ncnn-vulkan)
- [IFRNet](https://github.com/ltkong218/IFRNet) / [ifrnet-ncnn-vulkan](https://github.com/nihui/ifrnet-ncnn-vulkan)
- [AnimeInterp](https://github.com/lisiyao21/AnimeInterp)
- [EISAI](https://github.com/ShuhongChen/eisai-anime-interpolator)

## 7. 工作拆分与依赖顺序

### 7.1 依赖图

```text
共享基线：本文件 + 当前 STATUS + source-only 规则
  |
  +-- A. realtime-pipeline（立即开始，主关键路径）
  |     P0 instrumentation -> 单变量 A/B -> bounded pipeline/native pack
  |             |
  |             +-- C. anime-sparse-sr（A 的 telemetry/queue 合同合并后再创建）
  |
  +-- B. anime-model-lab（可与 A 并行）
        license/source audit -> offline common benchmark -> mobile shortlist
                    |
                    +-- 为 C 选择首个 SISR backend

A + B + C 的结果稳定
  |
  +-- D. anime-vfi（最后创建；默认离线、可选功能）
```

### 7.2 任务 A：实时流水线与观测

目标：先得到可信的端到端瓶颈分布，再实现一个可归因的吞吐改进。

首轮拥有范围：

- `app/src/main/java/dev/aisystems/quicksrplayerlab/QuickSrVideoEffect.java` 及其测试；
- Android benchmark telemetry、host validator 和 runner；
- 与运行时观测直接相关的文档。

完成门槛：

1. raw ns、队列/PTS/processed/drop/bypass 指标有测试并 fail closed；
2. baseline 与改动后使用同一 workload、APK/source/model identity；
3. 逐帧 correctness/hash 没有 stale output；
4. 报告收益、无收益或回归，不以“代码完成”代替真机证据；
5. 不改模型质量路线，不提交模型、APK、媒体、设备原始证据或 vendor binary。

### 7.3 任务 B：动漫模型实验室

目标：从 GPU shader、移动 SISR 和研究 VSR 中选出少量可以进入设备验证的候选。

首轮拥有范围：

- `pc-benchmark/`、模型来源/导出计划、rights-clear fixture manifest；
- 独立模型比较报告；
- 不修改 Media3/QNN 播放热路径。

完成门槛：

1. 先形成源码/权重/数据集三层许可矩阵；
2. 在同一 clean、blur/JPEG、line-art、subtitle 场景做同口径 A/B；
3. 保存参数量、输入输出布局、运行时、内存和转换难度；
4. 最终只晋级 1 个 GPU shader 候选和 1 个移动模型候选；
5. 没有权利或转换证据的候选明确标为 blocked，而不是下载后默认可用。

### 7.4 任务 C：cadence 感知稀疏 SR

只有任务 A 的 telemetry、generation 和 queue policy 合并到主线后才创建新工作树。这样它从正确的调度合同出发，不需要在旧 effect 上重复实现或制造大规模冲突。

完成门槛是混合 cadence/切镜/字幕/平移 fixture 的决策正确性、PTS/hash 追踪和端到端收益；固定 `frameIndex % 3` 不接受为产品实现。

### 7.5 任务 D：动漫插帧

只有 A/B/C 表明播放器仍有热预算、已选定 SR backend，且用户接受可选流畅模式后再创建。首轮只允许离线评测，不直接进入默认播放路径。

2026-09-03 执行状态：已在独立分支完成首轮 source-only 离线评测。该轮没有修改
`QuickSrVideoEffect` 或注册播放器 effect；实现了 hold/cut/seek/stream-epoch fail-closed
prefilter，并只把 RIFE ncnn Vulkan v4.6 晋级到一台 Adreno 740 上的独立 native CLI
有界探针。约 107 ms 的设备内 `RIFE::process` 中位 wall time不满足 24/30 fps 预算，
模型权重再分发、代表性动漫时序质量、人工审核、驻留 runtime、A/V sync 和长时热稳仍然
开放。详见 [ANIME_VFI_OFFLINE_EVALUATION.md](ANIME_VFI_OFFLINE_EVALUATION.md)。

## 8. 合并和新工作树规则

1. 本文档先在 `main` 形成共享基线提交；所有立即启动的工作树从该提交创建。
2. A 与 B 可以并行，因为文件所有权基本分离；若 B 需要修改 App，只提交接口提案，不直接改热路径。
3. A 先审核/合并；B 的报告可独立合并，模型集成另开提交。
4. C 不从今天的基线提前创建，而是在 A 合并后的 `main` 新建，避免依赖陈旧 telemetry 和 queue semantics。
5. D 最后创建；不能以 VFI demo 能运行来替代动漫质量、移动实时或许可证明。
6. 每个工作树只保留一个主要写入者；跨任务公共接口先在文档中冻结。
7. 工作树完成不自动等于可合并。必须单独审查 diff、测试、报告、source-only gate 和开放门禁。
8. 不自动 push；远端发布由主线审查后单独授权。

## 9. 立即执行的会话策略

现在只启动两个独立工作树会话：

1. `实时流水线：补齐端到端观测并验证吞吐优化`；
2. `动漫模型实验室：筛选移动端候选并建立同口径 A/B`。

`cadence 感知稀疏 SR` 和 `动漫插帧` 保持待创建状态。前者等待实时流水线合同进入主线，后者等待前三条路线给出质量和预算结果。这种拆分保留了有效并行度，同时避免四个会话同时修改播放器核心。
