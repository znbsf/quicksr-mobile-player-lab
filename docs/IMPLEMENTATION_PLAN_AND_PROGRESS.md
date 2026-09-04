# 动漫视频增强：实现计划与进展

状态日期：2026-09-04

实现与设备证据基线：`main` 的 `d7daad5`

文档角色：当前实现、证据等级、先后依赖和下一步的唯一总览。早期研究理由仍保留在
[动漫视频实时超分与插帧执行拆分](ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)，各项原始测量以链接的专项报告为准。

## 1. 当前结论

播放器主链已经能用：Media3/MediaCodec 解码、本地视频播放，以及原画、GPU Lanczos、
GPU-resident Anime4K x2 Small、QuickSR CPU 和 QuickSR QNN HTP 切换均已接入 App。
这不等于所有增强模式都达到产品级实时和画质门槛。

首个可交付目标仍是单台目标机上的本地 SDR `640×360 → 1280×720 @ 23.976 fps`，
而不是先承诺 1080p 神经输出。旧 smoke 曾达到 24.0134 fps 的播放器代理吞吐，但最新
raw-ns 合同下的 720p SERIAL/OVERLAP 总计 p95 仍超过单帧预算；固定同帧画质、最终显示、
A/V sync 和正式热稳也没有关闭。因此“吞吐接近源帧率”不能写成 M3 产品门禁已完成。

当前主要路线的裁决如下：

1. **Anime4K 已进入可选择播放器路径。** 单台 Android 16 / Adreno 740 已完成
   720p、1080p、1440p 的有界 model-active 功能运行；固定同帧参考、GPU timing、
   代表性动漫线条/字幕画质和长时热稳仍未关闭。
2. **动漫 cadence 复用已接入 QuickSR 热路径，但只允许 benchmark Intent 开启，默认和交互模式均为 `OFF`。**
   单台真机 720p A/B 中，680 帧有 257 帧复用，实测减少 37.79% 推理；它不插帧、不改 PTS，
   也不是固定“每三帧一次”。低对比字幕和代表性动漫人工画质仍是开放门禁。
3. **QNN inference/postprocess 重叠已实现为默认关闭的构建实验。** 720p 已受源帧率限制，
   平均代理吞吐仅提高约 0.6%；1080p 从 11.435 提高到 17.960 fps，但仍属 `offline`，
   p95 尾部变差且增加 23.73 MiB 输出张量，因此不能改成默认路径。
4. **JNI/NEON direct output packer 已完成 ABBA 并被否决为默认。** 同 APK 的 1080p
   SERIAL 比较中，平均吞吐从 Java 的 11.855 降至 6.765 fps，output-pack p50 从 36.566
   增至 102.353 ms；Java 保持默认，native 只保留显式实验路径。
5. **插帧仍未进入播放器。** RIFE v4.6、IFRNet-S 和 RIFE v4.25-lite 都只存在于独立
   native CLI/离线评测。IFRNet-S 与 v4.25-lite 已分别完成真机探针并停止；后者虽然在
   观察设备上兼容，但只在 256x144 更快，另两档慢 56.0-77.2%，没有一致替换收益。
6. **动漫画质主机合同已进入主线，但仍不是画质 PASS。** 六个空间 case 和 14 个时序
   case/74 帧已由 canonical generator、manifest 和 hash 约束；结果只允许写成
   `declared_oracle_conformance`，真实 Anime4K/cadence 运行、代表性动漫和人工审核仍待补。

2026-09-03 的 P0 真机裁决已经完成。三档均 `PASS`，每档两次进程的 14 个输出全部 hash
一致，进程退出干净；这证明限定设备兼容，不改变 `OFFLINE_ONLY` 或播放器未接线的边界。

## 2. 证据标签

| 标签 | 含义 |
| --- | --- |
| `IMPLEMENTED` | 源码已接线并有主机测试；不自动包含真机、画质或实时证明 |
| `DEVICE_BOUNDED` | 在明确的一台设备、片源、时长和代理指标下观察到；不可外推到其他设备或长时运行 |
| `OFFLINE_ONLY` | 独立工具或 CLI 已运行，但不在 Media3 播放器、A/V sync 或最终显示链内 |
| `PENDING` | 依赖、脚本或候选已准备，但缺少规定的下一份证据 |
| `STOPPED` | 当前证据不支持继续集成；只有新的模型、运行时或目标变化才重开 |

## 3. 实际接线图

```text
本地 SDR 视频
  -> Media3 / MediaCodec
  -> 用户选择
       |-- 原画
       |-- GPU Lanczos
       |-- Anime4K x2 Small（GL texture 内五段；已接播放器）
       `-- QuickSR CPU / QNN HTP
             -> 有界输入队列、generation/PTS/CRC 遥测
             -> cadence analyzer（仅 benchmark 可启用，默认 OFF）
             -> inference
             -> serial postprocess（默认）
                或 bounded overlap（构建开关，默认 OFF）
             -> Java output pack（默认）
                或 JNI/arm64 NEON direct pack（显式实验，默认 OFF）
             -> Media3 output-submit 代理

RIFE / IFRNet / ANVIL
  -> vfi-benchmark 独立研究路径
  -> 当前没有 Player Effect、UI 开关、A/V sync 或显示接线
```

主要实现入口：

- `app/src/main/java/dev/aisystems/quicksrplayerlab/SuperResolutionActivity.java`：播放器模式、
  Anime4K effect、QuickSR effect 和 benchmark-only cadence 配置；
- `app/src/main/java/dev/aisystems/quicksrplayerlab/QuickSrVideoEffect.java`：有界队列、
  generation/stream epoch、cadence 缓存所有权、QNN 推理和可选 postprocess overlap；
- `app/src/main/java/dev/aisystems/quicksrplayerlab/AnimeCadenceAnalyzer.java`：
  内容变化、字幕高对比 guard、最大连续复用 2 帧；
- `app/src/main/java/dev/aisystems/quicksrplayerlab/Anime4kSmallEffect.java`：
  GLES 五段图、RGBA16F 中间纹理、能力检查和两级回退；
- `app/build.gradle.kts`：`quickSrPostprocessOverlap` 和 `quickSrNativeOutputPacker` 默认 `false`；
- `vfi-benchmark/`：prefilter、host/device CLI 构建、常驻矩阵和候选证据；没有 App 接线。

## 4. 实现与证据进展

| 工作项 | 源码状态 | 当前最高证据 | 决策 |
| --- | --- | --- | --- |
| Media3 播放器与 QuickSR CPU/QNN | `IMPLEMENTED` | 单设备 720p/1080p/1440p/4K-display 功能矩阵 | 保留；实时、最终显示和长时热稳分开判断 |
| 逐帧 telemetry、generation、PTS、CRC、队列和生命周期 | `IMPLEMENTED` | 已用于 overlap/cadence 真机报告 | 继续作为所有热路径 A/B 的共同合同 |
| QNN postprocess overlap | `IMPLEMENTED`，默认 OFF | `DEVICE_BOUNDED`：1080p +57.1% 代理吞吐，但仍离线且尾部回归 | 保留实验，不设默认；native direct packer 已执行并失败，下一变量必须由分段测量重新选择 |
| JNI/NEON direct output packer | `IMPLEMENTED`，默认 OFF | `DEVICE_BOUNDED`：ABBA 功能/生命周期和 3 项 instrumentation 通过，但平均 FPS -42.94%、pack p50 +179.91% | 否决默认化；Java 保持默认，native 仅留可审计实验 |
| Anime4K x2 Small | `IMPLEMENTED`，UI 可选 | `DEVICE_BOUNDED`：三档 model-active，短片无 fallback | 先补固定同帧和 GPU timing，不急着换 Medium |
| cadence-aware SR reuse | `IMPLEMENTED`，仅 benchmark 可开 | `DEVICE_BOUNDED`：720p 减少 37.79% 推理，映射 motion false reuse 为 0 | 继续画质/复杂 cadence 门禁；暂不进普通 UI |
| Anime4K/cadence 画质合同 | `IMPLEMENTED`，主机工具已进入 `main` | `HOST_ONLY`：6 个空间 case、14 个时序 case/74 帧；只验证 declared-oracle conformance，runtime evidence=`NOT_BOUND` | 下一步接真实 offscreen GL/mpv/Android trace/receipt 和人工盲审；不能用 oracle-filled PASS 关闭画质门禁 |
| 动漫 SISR 候选筛选 | 工具与清单完成 | Anime4K 已进入设备门禁；SESR-M5 仅有来源/接口方案 | SESR 先做导出与 CPU/QNN 一致性，不直接接播放器 |
| RIFE v4.6 | 独立 CLI | `OFFLINE_ONLY`：五档单设备 resident 矩阵 | 作为冻结基线，不宣称播放器实时 |
| IFRNet-S | 独立 CLI | `OFFLINE_ONLY`：PSS 低约一半但三档更慢 | `STOPPED` |
| RIFE v4.25-lite | 独立 CLI | `OFFLINE_ONLY`：三档单设备 resident 矩阵；一档更快、两档明显更慢，PSS 均略高 | `STOPPED` |
| ANVIL | 来源和架构筛选完成 | 论文/源码表明需 H.264 motion vector + Vulkan + QNN | 暂不实现；若继续须另建 SM8550/V73 系统任务 |
| 播放器内 VFI | 未实现 | 无 | 在 kernel、动漫画质、许可、A/V sync 和热预算过门前不创建 |

专项证据：

- [QNN 后处理重叠 A/B](ANDROID_QNN_POSTPROCESS_OVERLAP_AB.md)
- [QNN native output packer ABBA](ANDROID_QNN_NATIVE_OUTPUT_PACKER_ABBA.md)
- [动漫 cadence 复用](ANIME_CADENCE_REUSE.md)
- [Anime4K Android GPU 集成](ANIME4K_ANDROID_GPU_INTEGRATION.md)
- [动漫 VFI 离线基线](ANIME_VFI_OFFLINE_EVALUATION.md)
- [移动 VFI 候选裁决](ANIME_VFI_MOBILE_CANDIDATE_PROBE.md)
- [RIFE v4.25-lite 新版运行时探针](ANIME_VFI_RIFE_V425_LITE_PROBE.md)

## 5. 下一步执行顺序

```text
P0 已完成
  -> RIFE v4.25-lite 三档 resident matrix
  -> 裁决：兼容，但无一致替换收益，STOPPED

P1A 已完成
  -> QuickSR SERIAL Java/native packer ABBA
  -> 裁决：功能通过但性能显著回归，native 默认化被否决

当前关键路径
  |-- P1B 画质：真实 Android/offscreen GL adapter + trace/receipt + 人工审核
  `-- P1C 播放：拆分吞吐/排队延迟分类，补 GPU completion/最终显示、A/V sync、生命周期和热稳

M3 共同检查点（先关闭 360p→720p）
  -> 当前代码按同一证据合同重跑，画质、最终显示、A/V 和持续运行均过门
  -> 任一核心门禁失败：保留实验状态；先修证据指向的瓶颈，不扩队列

条件性 P1D（M3 关闭后才追 1080p）
  -> 从 profiler 只选一个：输出数据布局/量化 I/O/shared allocator/显示域路径
  -> 固定模型、片源、tuning、队列和 cadence 做 ABBA；失败即停止该变量

条件性后续
  |-- SESR-M5：只在权利边界允许时做导出、CPU ORT、QNN parity
  `-- ANVIL：只在仍明确需要手机 VFI 时建立 H.264 MV + SM8550/V73 专项
```

### 当前主要矛盾与优化判据

**项目级主要矛盾是性能实验已经跑到 1080p，但首个 720p 产品声明所需的真实画质、最终显示、
A/V 和持续运行证据仍未闭合。** 继续换 packer、扩大队列或接入 VFI，不会关闭首个可交付
目标，反而会叠加新变量。当前优先级必须从“再找一个更快实现”切换为“先证明 720p 输出值得
显示、确实按时显示，而且可以持续运行”。

1080p 的技术子矛盾是全帧 float32 NCHW 在 GL、CPU、ORT/QNN 和 RGBA 显示域之间往返：

- 24 fps 单帧预算为 41.67 ms；既有 Java 基线的 QNN caller p50 约 44.7 ms，单项已接近或
  超过预算；
- Java output-pack p50 约 36.6 ms，约占一帧预算的 88%；SERIAL 把两项串联后只有
  11.855 fps；
- overlap 把 1080p 提高到 17.960 fps，却增加 23.73 MiB 输出张量并把 total p95 从
  536.44 ms 推高到 625.37 ms；
- native/NEON packer 已从数据上否决，不能再次作为默认化候选；Media3 固定 effect 缓冲、
  GPU completion 和最终 latch 仍未被分离测量。

这里还有一个指标矛盾：当前 validator 用单个 `performance_class` 同时要求 observed FPS 达标
和 `effectTotalToOutputSubmitProxyNs` p95 小于一帧，再把其余情况统一写成 `offline`。但项目使用
的 [Media3 1.11.0 `ByteBufferGlEffect`](https://github.com/androidx/media/blob/1.11.0/libraries/effect/src/main/java/androidx/media3/effect/ByteBufferGlEffect.java)
固定 `queueSize=6`、pending pixel-buffer queue 为 1，公开构造器只接收 `Processor`。所以
“流式吞吐跟得上”与“单帧排队/显示延迟小于 41.67 ms”是两条不同的轴；不能放宽现有严格
门禁，但应新增 `throughput_class`、`effect_latency_class` 和 `final_display_status`，保留旧
`performance_class` 只做兼容。否则优化可能只是降低队列积压，却被错误归因到 kernel，或
吞吐接近 24 fps 仍被一个含混的 `offline` 标签遮蔽。

因此下一轮不是直接实现另一个优化，而是先补一个只读测量/验收回合：对同一 720p 和 1080p
workload 分离 readback-ready proxy、preprocess、QNN caller、tensor copy、pack、GL submit、
GPU completion 与最终显示，并绑定真实输出。只有结果明确后才从以下方向选择一个：

1. 若 float 输出和布局转换主导，先验证 W8A8/uint8 或直接显示友好布局，避免再优化同一
   float NCHW→RGBA 循环；
2. 若跨 ORT/QNN 边界的复制/同步主导，再单独评估 shared allocator 或 native I/O；
3. 若 Media3 effect 缓冲或 GL 往返主导，再建立 texture-resident/更低缓冲路径的独立实验；
4. 若 QNN caller 本身仍越过预算，则降低 workload 或比较 SESR-M5，而不是靠并行队列掩盖。

候选必须沿用现有 fail-closed ABBA：两个 B 回合都达到预先冻结的吞吐目标；单帧 effect
延迟和最终显示另列，不能恶化对应 A 的尾延迟；CRC/PTS/generation/lifecycle 必须无回归，
并报告额外 PSS。任何一项失败都保留为负结果，不与 overlap、cadence 或新模型混测。

立即执行包不修改热路径，先完成以下三步：

1. telemetry/report schema 向后兼容地新增 `throughput_class`、`effect_latency_class`、
   `final_display_status`，并记录 Media3 effect queue=6、pending PBO=1；旧
   `performance_class` 和阈值保持不变，避免重写历史结论；
2. 将实际 Android/offscreen GL 输出接入既有 visual contract，生成逐帧 trace、receipt、
   source/output hash；这一变更不同时调整队列、模型或 packer；
3. ADB 重新可见后，先用当前默认路径重跑 720p，再根据独立的吞吐、排队延迟、最终显示、
   A/V 和画质结果决定是否需要低缓冲自定义 effect。Media3 1.11.0 的公开
   `ByteBufferGlEffect` 不能直接配置队列；如确有必要，应另建 queue=2/6 单变量原型，不能靠
   反射改私有常量。

### P0：RIFE v4.25-lite 真机裁决（已完成）

唯一已授权的 arm64 物理设备确认为 Android 16 / SM8550 / Adreno 740。矩阵使用
`160x90`、`256x144`、`320x180` 三档和真实 128 像素 padding，延迟进程与内存采样进程
分离。输入、manifest、prefilter、设备侧 hash、完整 task timing 和两次输出一致性均通过。

| 档位 | v4.25-lite median / max | 相对 v4.6 median | PSS 变化 | 合成质量方向 |
| --- | ---: | ---: | ---: | --- |
| 160x90 | 53.614 / 64.132 ms | +77.2% | +1.6% | 三项均退步 |
| 256x144 | 36.933 / 47.775 ms | -18.7% | +1.0% | 三项均退步 |
| 320x180 | 55.716 / 58.820 ms | +56.0% | +1.8% | 三项均改善 |

裁决为 `STOPPED`：一档速度收益不足以抵消另两档大幅回归、全档 PSS 增长和混合质量结果；
而且没有一档保有完整播放器余量。结果详见
[RIFE v4.25-lite 新版运行时探针](ANIME_VFI_RIFE_V425_LITE_PROBE.md)。

### P1A：QuickSR native direct packer（已完成）

以默认 SERIAL 1080p 为基线，只替换 float NCHW 到可上传 RGBA direct buffer 的 pack/copy
实现；模型、QNN tuning、队列、输入片源、cadence 和 profile 全部固定。先做 Java/native
逐帧 hash 与边界尺寸测试，再做至少 ABBA 真机比较。重点看 output-pack p50/p95、总计、
吞吐、PSS、drop/bypass、generation/seek 和释放行为。

ABBA 已完成。244 次可对齐跨路径重复没有输出 CRC 冲突，覆盖 180 帧周期中的 70 个不同
identity；B1/B2 的确定性 Java/native 启动自检均为 PASS。功能门禁、队列、drop/bypass 和
生命周期通过；修正 runner 后的 3 项真机 instrumentation 也全部通过，范围仅为 packer
数值一致性、边界映射与缓冲区 ownership。平均 FPS 仍下降 42.94%，pack p50 增长 179.91%，
因此保留 Java 默认。
结果见 [QNN native output packer ABBA](ANDROID_QNN_NATIVE_OUTPUT_PACKER_ABBA.md)。

### P1B：代表性画质与 cadence 安全性

Anime4K 不再依赖 OEM 截图或 UI 位置。应从确定的 effect 输出或受控离屏 GL fixture 获取
同帧，和固定 mpv/上游参考比较 clean、blur/JPEG、线稿、字幕边缘，再进行盲审。

cadence 需要增加一拍一/二/三混合、慢速平移、嘴型、粒子、硬切、淡入淡出、高/低对比字幕
夹具，检查错误复用率、漏复用率、PTS、reference identity 和人工节奏感。已通过的 BBB
15→24 重复映射只证明当前片源上的 hold 判断，不能代替动漫语义质量。

主机侧夹具和 fail-closed declared-oracle evaluator 已完成：两种既有退化下生成上述序列，
从版本控制源码重建 canonical contract，并比较提交声明/文件中的同帧 hash、复用、PTS、
reference/output identity，同时输出 PSNR、global SSIM 和 edge MAE。这些字段来自 submission
自报，不能当作真实 analyzer 错误/遗漏复用率；真实运行仍需可重放逐帧 trace、receipt 与执行
身份联合绑定。本轮没有执行 mpv、Android/GL 或真机输出，也没有人工盲审，因此 P1B 视觉
门禁仍未关闭；详见 [主机画质门禁](ANIME_VISUAL_QUALITY_GATES.md)。

### P2：模型和 VFI 的条件性路线

- **SESR-M5**：先在忽略目录完成固定 640x360->1280x720 导出，比较 source/CPU ORT/QNN
  tensor；DIV2K 学术研究边界和 APK/模型分发仍由人工审核。未过 parity 前不改 App。
- **Anime4K Medium**：只有 Small 的盲审显示明确不足且 GPU budget 有余量才比较；不能仅因
  pass 更多就视为更好。
- **ANVIL**：不能塞进现有 PNG pair runner。若启动，必须拥有真实 H.264 motion-vector
  side data、SM8550/V73 QNN Stub/Skel/context、Vulkan/QNN 生命周期和播放器级 A/V 合同。
- **播放器 VFI**：RIFE/IFRNet CLI 成功不是集成许可。至少需要代表性动漫时序质量、可分发
  权重、真实播放器余量、seek/flush、A/V sync、最终显示和 10～30 分钟热稳，才允许创建。

## 6. 会话与工作树安排

当前 `main` 已包含这轮有价值的实现和报告；旧实验工作树仍存在，但不是继续开发的权威基线，
也不在本计划中自动删除。新工作应从当时最新且干净的 `main` 创建，不从旧工作树叠加。

1. **P0 已在主线完成，不继续 RIFE v4.25-lite 集成。** 既有离线 runner 和负结果保留，
   避免后续会话重复探测同一候选。
2. 两个互不重叠的工作树已经执行：`visual-quality-gates` 经审查修正三项 P1 后已合入
   `main`；`native-output-packer` 已完成真机 ABBA、负性能裁决和设备测试。主线复核后保留
   Java 默认，native 只作为显式、默认关闭的可审计实验路径。
3. SESR 或 ANVIL 一次只启动一个。二者会引入新的 runtime、模型和许可证据，不与播放器
   热路径实验混在同一分支。
4. 各任务完成后先回报结果，再由主线审查 patch equivalence、测试、证据边界和 source-only
   gate。工作树完成不自动等于合入、默认启用或发布。
5. 不自动 push；远端仍落后于本地 `main`，发布需要单独授权。

## 7. 本次盘点验证

- `:app:testDebugUnitTest`：107/107 PASS；
- Python：`scripts` 44/44、`golden-correctness` 14/14、`pc-benchmark` 27/27、
  `derived-models` 19/19、`vfi-benchmark` 12/12，共 116/116 PASS；需要 ONNX/ORT 的三组
  使用仓库固定 Python 环境；
- source-only publication scan 204 项 PASS；oracle 重签和任意 PPM 冒充
  的对抗路径均由测试覆盖，runtime evidence 仍固定为 `NOT_BOUND`；
- 本次同步即时重跑了 JVM 单测、五组 Python 测试和 publication scan；lint、assemble 与
  三项真机 instrumentation 沿用 `d7daad5` 已记录的最近一次完整门禁结果，没有伪写成本轮重跑；
- 本次同步检查时当前 shell 的 ADB 在线设备数为 0，因此没有新增真机运行；`d7daad5` 前已经
  记录的 RIFE v4.25-lite 三档 PASS、42 个输出逐项 hash 一致和 packer ABBA 仍作为历史证据，
  但下一轮设备门禁必须先重新确认目标机可见；
- 原始报告保留在忽略目录，提交只记录去标识汇总；设备兼容没有被扩写成播放器实时成功。

## 8. 计划更新规则

每完成一个阶段，只更新四件事：状态标签、实际证据、裁决和下一依赖。不得用“代码已写”、
“shader 编译”或“CLI 能启动”替换真机执行、最终显示、画质、许可或长时热稳。负结果和停止
路线保留在表中，避免后续会话重复走同一条路。
