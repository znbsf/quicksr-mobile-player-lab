# QuickSR Mobile Player Lab

面向 Android 的图片与视频超分实验 App，重点验证一条可落地的动漫超分路线：先在 PC 上完成倍率、退化、画质和性能筛选，再把合适的模型放到 Qualcomm QNN HTP/NPU 上做实时播放验证。

当前版本：**v0.15.0**。仓库采用 **source-only** 策略，不提交未授权模型权重、APK、测试媒体、Qualcomm 二进制或原始设备日志。唯一随源码保留的神经 shader 是经单独审查、保留 MIT notice 且由 commit/bytes/SHA-256 固定的 Anime4K x2 Small 上游文本。

## 核心能力

- 图片和视频超分；
- 覆盖 16:9 与方形的 360p、480p、720p 输入；
- 输出路线覆盖 1080p、1440p 和 4K 显示；
- 集成 QuickSRNetSmall 1.5×、2×、3×、4× 模型流程；
- 支持 ONNX Runtime CPU、GPU Lanczos、GPU-resident Anime4K x2 Small 和 Qualcomm QNN HTP/NPU；
- 1080p QNN 默认使用 float32 NHWC 输出、双 pinned ORT output、deferred copy、四条带 pack 和同尺寸 Media3 output texture 直传；
- 提供默认关闭的动漫 cadence 感知超分复用实验：不插帧、不改 PTS，最多连续复用 2 帧；
- 建立 18 条 PC 动漫超分路线和 72 个权利清晰评测案例；
- 自动统计 PSNR、SSIM、边缘误差、阶段耗时、p50/p95 以及 24/30 FPS 性能等级。

### Android 视频档位

| 档位 | 神经输入与输出 | 定位 |
| --- | --- | --- |
| 720p | `640×360 → 1280×720`，2× | 性能归因与回归诊断档 |
| 1080p | `640×360 → 1920×1080`，3× | 手机 QNN 产品硬门与首要验证档 |
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

当前真机负结果已经表明“把中间流程全部换成 C”本身不能解决问题：JNI/NEON packer 和 direct FloatBuffer 路线均更慢。PBO 能降低 CPU 侧 GL 提交尾延迟，但没有提高严格显示通过率，因此默认关闭。后续优化必须先用关联 trace 定位偶发长帧，再针对线程调度、GL fence、BufferQueue 或共享缓冲中的一个环节动手。

## 当前结果

- 18 条路线覆盖：16:9 与 1:1 的 360p/480p/720p → 1080p/1440p/4K；方形素材采用等比缩放加留边，不拉伸。
- 72 个权利清晰案例中，干净输入 QuickSR PSNR 胜 30/36；模糊 0.6 加 JPEG Q35 仅胜 7/36；方形漫画胜 16/18。
- 结论：QuickSRNetSmall 更适合干净线稿和漫画；严重模糊或压缩素材应回退 Lanczos，或换用针对退化训练的模型。
- 已存档的 v0.12.0 单机 720p QNN smoke：`645 帧 / 26.860 秒 = 24.0134 FPS`，四个约 5 秒 MediaCodec 窗口 Drop=0。
- API 35 x86_64 模拟器已验证 1080p、1440p 和 4K 显示路径的实际纹理尺寸，但模拟器 CPU 时间不能外推到手机 NPU。
- 当前最终默认版在一台物理 Qualcomm 设备、权利清晰 30fps 片源上完成 1080p 吞吐：828 个稳态样本、30.0045fps、effect drop/bypass 0；这是 output-submit 吞吐代理，不是最终显示结论。
- 同 APK 的 SurfaceFlinger ABBA 中，原画 2/2 PASS，QuickSR QNN 1/2 PASS；失败轮平均仍为 30.0341fps，但出现 58.153ms 长间隔和补偿短间隔，因此仍不能声称“保证原片帧率”。
- v0.15.0 的 Anime4K v4.0.1 x2 Small 已在一台 Android 16 / Adreno 740 设备完成 720p/1080p/1440p 有界播放：三档首帧均 model-active、MediaCodec Drop=0、PSS 峰值约 173-175 MiB、温度代理保持 38.9-39.0 C；同位置视觉 A/B 未可靠取得，仍不声称与 mpv 输出等价或具有通用实时性能。
- 由实际 Java 适配器生成的五段 model fragment 与 `mediump` fallback 已在 Android Emulator 随附的 SwiftShader OpenGL ES 3 环境 6/6 compile+link PASS；同一宿主 context 的 half-float 扩展预检和 RGBA16F FBO completeness 也通过。这仍只是 DLL 级主机 smoke，不是模拟器 App 或目标手机执行。

完整证据边界见 [项目状态](docs/STATUS.md) 和 [完成度审计](docs/GOAL_COMPLETION_AUDIT.md)。当前代码接线、已完成/停止/待执行项及工作树顺序见 [实现计划与进展](docs/IMPLEMENTATION_PLAN_AND_PROGRESS.md)。Anime4K pass、颜色适配、回退与真机门禁见 [Android GPU 集成说明](docs/ANIME4K_ANDROID_GPU_INTEGRATION.md)；研究理由和原始任务拆分保留在 [动漫视频实时超分与插帧执行计划](docs/ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)。
cadence 实验的队列、generation、缓存所有权、运行时开关和代理指标边界见 [动漫 cadence 感知复用说明](docs/ANIME_CADENCE_REUSE.md)。

## 阶段学习报告：从完整链路到 1080p 30fps

本节记录项目从最初“先证明 QNN 能运行”，到当前“平均处理吞吐达到 30fps”的完整演进。
它既是实现总结，也是后续避免重复试错的工程笔记。所有性能数字都是特定设备、APK、
片源和统计口径下的有界结果。

### 当前处理的输入与输出

当前 1080p 产品主档不是把任意源分辨率直接塞进模型，而是先建立固定形状合同：

| 边界 | 当前格式 | 大小或语义 |
| --- | --- | --- |
| 解码输入 | MediaCodec/Media3 SDR 视频纹理 | 本地、非 DRM；保留源 PTS |
| 模型前图像 | RGBA8 | Media3 缩放到 `640×360` 后读回 |
| QNN 输入 | float32 NCHW `[1,3,360,640]` | RGB 归一化到 float，约 2.64MiB；alpha 不进模型 |
| QNN 输出 | float32 NHWC `[1,1080,1920,3]` | QuickSRNetSmall 3× RGB，约 23.73MiB |
| 显示前输出 | RGBA8 `1920×1080` | RGB clamp/量化；alpha 从输入按最近邻映射 |
| Media3 输出 | `1920×1080` output texture + 原始 PTS | 同尺寸档直接上传，不再经过额外 scale blit |
| 最终呈现 | SurfaceView / SurfaceFlinger | 当前只取得 layer actual-present 代理，不是光子时序 |

其他视频档位复用同一 `640×360` 输入：2× 得到 720p，4× 得到 1440p；“4K 显示”仍是
3× 神经生成 1080p，再由 GPU 放大到 4K，不是神经原生 4K。图片路径则是完整图片 2×，
可按内存限制分块，与上述视频固定帧路径不是同一个性能合同。

### 当前实际效果

最终默认 APK 的 1080p、30fps QNN 路径得到：

| 指标 | 当前结果 | 正确解释 |
| --- | ---: | --- |
| measured frames | 828 | 丢弃 warm-up 后的结构化样本 |
| effect output-submit throughput | 30.0045fps | 流水线填满后的平均交付率 |
| source PTS coverage | 约 1.0000 | 没有靠系统性跳源帧换取速度 |
| effect drop / bypass | 0 / 0 | 本轮没有计算诱发的丢弃或旁路 |
| QNN caller p50 / p95 | 27.884 / 30.736ms | 调用方 wall time，不是纯 NPU kernel |
| NHWC→RGBA pack p50 / p95 | 11.321 / 15.503ms | 与 QNN lane 重叠，不能直接与 QNN 相加推导 fps |
| GL upload submit proxy p95 | 6.987ms | CPU 提交时间，不是 GPU completion |
| pipeline latency p95 | 179.024ms | 多帧流水线延迟，不是单帧服务时间 |

SurfaceFlinger 的最终显示对照仍未全过：原画 A1/A2 为 2/2 PASS，QuickSR QNN B1/B2
为 1/2 PASS。QNN 失败轮平均仍是 30.0341fps，但出现一个 58.153ms 长间隔和两个补偿
短间隔。因此当前效果应表述为：

> **1080p 平均处理吞吐已经达到 30fps；最终显示仍偶发节奏抖动，尚不能保证原片帧率。**

画质方面，历史 72 个权利清晰 PC 案例表明 QuickSR 更偏好干净线稿/漫画，严重模糊和
JPEG 压缩输入优势明显下降。当前手机结果没有完成代表性动漫同源同帧盲审，所以不能把
“跑到 30fps”写成“画质已经优于 Anime4K 或原片”。

### 从项目开头到现在采用的优化手段

#### 1. 先建立可复现的模型与硬件执行合同

- 把动态模型派生为固定 shape，冻结模型 bytes、SHA-256、输入/输出名和布局；
- 复用 ORT session、input tensor、output tensor 和 direct buffer，避免逐帧建图与分配；
- QNN HTP 使用明确 tuning，并关闭 CPU EP 静默 fallback；
- 区分“QNN strict 会话配置”“per-node placement”“最终显示”，不把其中一个当作全部证明；
- 用 runId、APK/source/model hash、frameId、generation、PTS 和 CRC 绑定每次运行。

这一步没有直接制造高 fps，但解决了“到底跑的是不是当前 APK、当前模型和 HTP”的问题，
为后续所有优化提供了可信基线。

#### 2. 先保留硬件解码和播放器时间轴

- 保留 Media3/MediaCodec 的硬件解码、PTS、seek、Surface 与生命周期；
- 用 `Presentation` 先创建目标尺寸画布，避免神经输出又被播放器缩回输入尺寸；
- 加入 Original 和 GPU Lanczos 基线，使 QNN 显示抖动可以与播放器自身区分。

结论是：重写整个 decoder 不是最初的主要矛盾；原画最终显示 2/2 PASS 也证明当前残留
长短帧只在 QNN 增强路径出现。

#### 3. 从串行处理改成有界流水线

最早的热路径把 preprocess、QNN、output copy、RGBA pack 和 GL 交付串在同一 worker。
之后改为固定容量的三段流水：

```text
inference lane:   preprocess(N+1) -> QNN(N+1)
postprocess lane: output copy/pack(N)
Media3 GL lane:   upload/output-submit(N-1)
```

frame-admission 容量保持 2，postprocess 只有一个 active task 和一个 queued task。每个 slot
绑定 PTS/generation，seek/flush 后旧帧不能混入新时间轴。1080p 阶段观测从串行约
11.435fps 提升到早期 overlap 的 17.960fps；这证明流水线方向有效，也证明仅有两段重叠
仍不足以达到目标。

#### 4. 优化输出布局，而不是只优化同一个 NCHW 循环

旧 1080p 输出是 NCHW：同一像素的 R/G/B 分布在三个相距约 207 万元素的平面。项目派生
了同权重 float32 NHWC 输出，使每个像素的 RGB 连续排列，再按固定行区间并行打包。

- 四条带成为默认；
- 两条带真机 output-pack p95 约 22.6ms，慢于四条带约 16ms，停止；
- 预计算 alpha 的 x/row 映射，移除每个输出像素的重复乘除；
- 仍保持任意输入 alpha 的逐字节合同，不用“强制 alpha=255”换取虚假收益。

这一改动与后续并行共同把最终版 pack p50/p95 收敛到 11.321/15.503ms。

#### 5. 把 ORT 输出复制移出 inference 关键路径

默认维护两个 pinned ORT output tensor。首帧仍同步复制并完整检查有限值；从第二帧开始，
QNN 完成后把 output lease 交给 postprocess lane 做 bulk copy，inference lane 可开始下一帧。

同一真机 A/B/B/A 中，control 与 candidate 都维持 30fps；candidate 将 inference caller p95
平均从 34.733ms 降到 30.529ms，将 accepted→output-submit p95 从 195.203ms 降到
178.195ms。代价是增加一个 1080p float output，约 23.73MiB。收益可重复且直接增加 30fps
余量，因此设为默认。

#### 6. 移除稳态诊断停顿

旧路径每约 120 帧完整扫描一次 1080p float output 的非有限值，真机可造成约 330～353ms
停顿。现在只在第一份成功输出上执行完整 finite scan；模型/runtime/layout 在会话可用前仍
fail closed，稳态不再周期性暂停。CRC 和结构化时间线继续保留，但不能把昂贵的诊断工作
混入产品热路径。

#### 7. 删除同尺寸输出中的冗余 GL 环节

旧路径先上传 private neural texture，再 blit 到 Media3 output texture。720p、1080p 和
1440p 的神经输出与画布相同时，现在直接写 Media3 output texture；只有 1080p→4K 显示
回退保留中间纹理与缩放。一次观测中 GL submit p95 从 6.435ms 降到 3.460ms，严格显示
通过数也从此前 QNN 0/2 改善到 1/2，因此保留该简化。

#### 8. 试验 PBO，但根据最终显示结果否决默认化

双 GL pixel-unpack PBO 把 CPU 侧 GL submit p95 从 3.460ms 降到 1.832ms，却增加约
15.82MiB，并且 SurfaceFlinger 仍只有 1/2 PASS。它说明“某一段计时更快”不等于产品门
已经改善。PBO 代码保留为显式研究开关，默认关闭，不再继续尝试更多 PBO 数量。

#### 9. 用负结果停止错误方向

| 尝试 | 结果 | 学到的结论 |
| --- | --- | --- |
| JNI/arm64 NEON output packer | 旧 ABBA 中 pack p50 36.566→102.353ms，平均 fps -42.94% | native 语言本身不保证更快；跨 JNI 与访存布局更重要 |
| direct FloatBuffer→native pack | 约 116.5ms | “少一次 copy”可能换来更差的逐元素 direct-buffer 访问 |
| Java direct IntBuffer/FloatBuffer | 主机/模拟器明显慢于 heap 顺序路径 | Java direct buffer 适合作为批量 I/O 边界，不适合逐元素热循环 |
| 扩大队列 | 未作为优化采用 | 队列只能隐藏积压并增加延迟，不能提高真实服务率 |
| 同一 QNN session 多 inference worker | 未采用 | 同一 graph 仍串行，还会破坏 tensor 所有权 |
| temporal batch=2 | 主机方向收益约 4.2%～16.9%，增加一帧等待和大输出 | 不足以关闭缺口，不接播放器 |
| 空间 batch/左右分块 | 主机约 -2.3%～+0.7% | 仅保留为未来双 graph 正确性基础 |

#### 10. 利用动漫时序冗余，但不把它冒充逐帧性能

cadence analyzer 可识别一拍二/一拍三式 held frame，在冻结 720p 映射中减少 37.79% 推理。
它仍为每个原始 PTS 输出一帧，不是固定“隔三帧超分一次”，并用字幕、运动、切镜与最大
连续复用 2 帧作为 guard。因为真实低对比字幕和复杂动漫误复用尚未完成审核，普通播放与
当前 1080p 帧率门均保持 `OFF`。最坏内容必须仍能逐帧处理，不能靠 cadence 掩盖算力不足。

#### 11. 保留 GPU-resident 与更轻模型作为产品备选

Anime4K x2 Small 的五段 shader 全程留在 GL texture，避开 QuickSR 当前的
`GL→CPU→QNN→CPU→GL` 往返；它已经进入播放器并取得单设备功能证据。SESR-M5 等轻量
动漫候选已完成研究审计但尚未接 App。只有 QuickSR 最终显示或后续画质门失败，才切换模型
主线，避免在根因未明时同时更换模型与架构。

RIFE、IFRNet 和 ANVIL 等插帧候选目前只在独立 CLI/离线路径；它们不能帮助 QuickSR 保持
原片帧率，反而增加额外推理负担，因此排在超分帧率、画质、A/V sync 和 thermal 之后。

### 已经做好的部分

- 图片完整 2× 与视频多档 QuickSR CPU/QNN 路径；
- Media3 硬件解码、原画/Lanczos/Anime4K/QuickSR 模式切换；
- 固定 shape 模型、hash 校验、persistent tensor、QNN strict/fail-closed；
- 有界队列、generation/PTS 所有权、seek/flush/release 回收和逐段 telemetry；
- 1080p NHWC、四条带 pack、双 pinned output/deferred copy、首帧 finite scan；
- 同尺寸 Media3 output texture 直传；
- 1080p 30fps 平均处理吞吐与零 effect drop/bypass；
- Original/QNN SurfaceFlinger ABBA 工具与去标识机器可读证据；
- Anime4K GPU 路径、cadence 原型、模型/VFI 离线研究与 source-only 发布扫描。

### 仍未做好的部分

- QNN 最终显示仍为 1/2 PASS，不能保证逐帧稳定 30fps；
- 尚未取得带 frameId/PTS 关联的 Perfetto、GL fence、BufferQueue 与 FrameTimeline 根因 trace；
- 当前 30fps 片源无音轨，没有 A/V sync 证明；
- 当前默认架构缺少 10～30 分钟 thermal、功耗与频率稳定性；
- 没有完成代表性商业动漫、字幕、颗粒和复杂编码的同源同帧盲审；
- 没有证明跨 Qualcomm 设备兼容，也没有实现真正的 GL/QNN shared-buffer zero-copy；
- cadence 与插帧尚未进入正式播放器默认路径；
- 尚未拆成可供其他 App 使用的稳定 AAR。

### 当前最重要的工程认识

1. **吞吐和延迟是两件事。** 三段流水可达到 30fps，但仍有约 179ms 的多帧 p95 延迟。
2. **平均 fps 和稳定显示是两件事。** 30.0341fps 的失败轮仍会出现 58ms 长帧。
3. **减少步骤必须看最终消费者。** PBO 的局部指标变好，却没有改善 SurfaceFlinger 通过率。
4. **并行必须有固定所有权和背压。** 无界队列只会把性能问题变成延迟和内存问题。
5. **负结果也是资产。** native packer、direct buffer、两条带、batch 和 PBO 已有证据，不应重跑排列组合。
6. **先定位主要矛盾，再换模型。** 当前下一步是关联一次失败长帧，而不是继续堆测试或同时接入 VFI。

下一步只采一份能关联同一 frameId/PTS 的 Perfetto/FrameTimeline 失败窗口，将长帧归因到
result-ready、GL fence、BufferQueue/latch 或 OS 调度中的一个域。归因后只实现对应的一条
路径，再执行一次 Original A1 → QNN B1 → QNN B2 → Original A2。完整计划见
[实现计划与进展](docs/IMPLEMENTATION_PLAN_AND_PROGRESS.md)，本轮去标识数据见
[1080p 物理机证据](docs/evidence/realtime-1080p-physical-20260905.json)。

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

720p 只作诊断；1080p 是当前产品硬门。1440p 与 4K 显示档应先观察内存、排队和温度，不建议默认开启。

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
- 分别输出功能门禁以及 `effect_proxy_realtime_30_throughput`、
  `effect_proxy_realtime_24_throughput`、`effect_proxy_below_source_cadence` 性能分类；前两者只覆盖 effect output-submit
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
