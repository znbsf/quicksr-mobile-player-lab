# 动漫视频增强：实现计划与进展

状态日期：2026-09-05

文档角色：当前唯一总计划。研究背景见
[动漫视频实时超分与插帧执行拆分](ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)，本轮架构细节见
[1080p 实时架构优化审计](REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md)。

## 1. 当前结论

第一硬门始终是帧率，不是画质。当前状态已经从“1080p 最高不到 20fps”推进到：

- 30fps effect output-submit 吞吐：`PASS`，最终默认版 `30.0045 fps`；
- 处理链 effect drop/bypass：`0/0`；
- SurfaceFlinger actual-present 严格 ABBA：原画 `2/2 PASS`，QuickSR QNN `1/2 PASS`；
- 因此“平均处理速率达到原片帧率”已完成，但“最终屏幕无长短帧、可保证原片帧率”未完成。

现在的主要矛盾不是 QNN 平均算力，而是偶发最终显示尾延迟：失败轮平均仍为
`30.0341 fps`，但出现 `58.153 ms` 长间隔和补偿短间隔。后续只做能定位这一事件的工作，
不再无目标增加模型、线程、队列和测试轮数。

## 2. 当前默认实现

```text
Media3 / MediaCodec
  -> 640x360 RGBA readback
  -> float32 NCHW input
  -> QNN HTP
       pinned input
       two pinned float32 NHWC output slots
  -> postprocess lane deferred bulk copy
  -> four fixed row stripes: NHWC -> RGBA8
  -> direct upload into same-size Media3 output texture
  -> SurfaceFlinger
```

默认值：

| 配置 | 默认 | 理由 |
| --- | --- | --- |
| `quickSrPostprocessOverlap` | `true` | QNN 与上一帧后处理并行 |
| `quickSrFloatNhwcOutput` | `true` | 连续 RGB 读取，适合条带 pack |
| `quickSrPackStripes` | `4` | 真机快于 2 条带 |
| `quickSrDeferredOutputCopy` | `true` | ABBA 降低 inference 与总提交尾延迟 |
| direct Media3 output upload | 同尺寸档启用 | 删除一张中间纹理和一次 scale blit |
| `quickSrPboUpload` | `false` | GL 代理更快但最终显示通过率无改善 |
| cadence reuse | `OFF` | 不能用内容复用掩盖逐帧硬门 |
| JNI/NEON packer | `false` | 真机显著回归 |

默认改动增加一个 pinned ORT output，约 23.73MiB。队列保持固定上界 2，不能通过无限积压
制造表面帧率。

## 3. 进展总表

| 工作项 | 状态 | 当前证据 | 下一依赖 |
| --- | --- | --- | --- |
| Media3 播放器、原画、Lanczos、Anime4K、QuickSR CPU/QNN | `IMPLEMENTED` | 播放器路径已接入 | 不扩播放器功能，先关显示节奏门 |
| 1080p QNN 平均吞吐 | `DEVICE_BOUNDED PASS` | 最终版 828 帧、30.0045fps、drop/bypass 0 | 保持回归，不重复长测 |
| 1080p 最终显示节奏 | `ACTIVE / FAIL` | 原画 2/2；QNN 1/2 | 捕获失败窗口 Perfetto/FrameTimeline |
| NHWC + 四条带 pack | `DEFAULT` | pack p50/p95 11.321/15.503ms | 仅作回归基线 |
| deferred ORT output copy | `DEFAULT` | 2×2 ABBA 均保持 30fps，inference p95 平均降约 4.2ms | 监控内存/thermal，不继续加槽 |
| direct output texture upload | `DEFAULT` | GL p95 曾从 6.435 降到 3.460ms；显示由 0/2 改为 1/2 | 保留；仍需定位剩余尾抖动 |
| 双 PBO upload | `NONDEFAULT / STOPPED` | GL p95 1.832ms，但显示仍 1/2，另增约 15.82MiB | 不再调 PBO 数量 |
| 首帧完整 finite scan | `DEFAULT` | 删除稳态周期性 330～353ms 扫描停顿 | 首帧仍严格校验 |
| JNI/NEON direct packer | `STOPPED` | 旧 ABBA pack p50 36.566→102.353ms；新 direct FloatBuffer 约 116.5ms | 不重开 |
| cadence 一拍二/三复用 | `IMPLEMENTED / OFF` | 既有 720p 冻结映射减少 37.79% 推理 | 显示硬门后做代表性动漫误复用审核 |
| Anime4K x2 Small | `IMPLEMENTED` | 单设备功能路径已运行 | QuickSR 帧率门后做同帧画质/GPU timing |
| SESR-M5 等轻模型 | `RESEARCH READY` | 候选审计已完成，尚未接播放器 | 只有 QuickSR 停止或质量失败才启动 |
| RIFE / IFRNet / ANVIL 插帧 | `OFFLINE ONLY` | CLI/真机候选探针有正负结果 | SR 帧率与画质之后再建播放器分支 |
| AAR | `NOT STARTED` | 无稳定公共 API | 产品路径两硬门后再拆 |

## 4. 这轮测试各自回答什么

| 测试 | 唯一问题 | 已得到答案 | 是否继续重复 |
| --- | --- | --- | --- |
| 30fps throughput | 管线服务率是否达到源 fps | 是，30.0045fps | 否，改动回归时才跑 |
| deferred A/B/B/A | 移动 ORT output copy 是否增加余量 | 是，尾延迟下降 | 否，已默认化 |
| direct texture A/B | 删除中间 blit 是否有益 | 是，GL 尾延迟下降 | 否，已默认化 |
| PBO A/B | 更快 GL 提交能否修掉显示抖动 | 否 | 停止 |
| SurfaceFlinger A/B/B/A | 平均 30fps 是否等于稳定显示 | 否；QNN 仍偶发长短帧 | 只在有定位性实现后复测 |
| 24fps 120 秒 | 常见 24fps 是否可持续 | 既有最终前一版已通过 24.001fps | 本轮重复计划已中止 |

后续不再用“多跑几遍也许通过”处理 1/2 的结果。下一份测试必须带能把失败帧映射到线程、
fence、BufferQueue 或 SurfaceFlinger 的时间线。

## 5. 下一步顺序

### P1：关联一次失败长帧

目标：解释 `58.153 ms` 长间隔为何发生，而不是再确认它存在。

需要同时采集：

1. app inference/postprocess/GL 三条 lane 的 frameId/PTS 时间戳；
2. Perfetto sched/freq、RenderThread、GPU fence；
3. BufferQueue 和 SurfaceFlinger FrameTimeline；
4. 失败帧前后约 1～2 秒的小窗口。

输出必须把同一 frameId 分到以下一类：result-ready 晚、GL-submit/fence 晚、latch 错过、
或系统调度/降频。只要不能关联，就保持 `INCONCLUSIVE`，不写根因。

### P2：只实现 P1 指向的一条路径

- result-ready 晚：检查线程优先级/deadline 与最后一次 CPU pack；若必须跨域，做最小
  AHardwareBuffer/shared-memory proof。
- GL/fence 晚：尝试 EGLImage/AHardwareBuffer texture-resident 输出，目标是消掉 RGBA8 回传上传。
- Media3 内部队列晚：在隔离 worktree 中做 queue capacity 单变量本地 fork。
- OS 抢占/降频：固定性能与 thermal 证据；若仍不稳定，停止这台设备上的 QuickSR 实时路线。

### P3：重跑唯一验收

实现后只跑：原画 A1 → QNN B1 → QNN B2 → 原画 A2。QNN 两轮均满足平均 cadence、无
`>1.5` 或 `<0.5` 源帧间隔、drop/bypass 0，才进入有音轨 A/V sync 与 10～30 分钟 thermal。

### P4：画质，再决定模型路线

帧率门通过后，用同源同帧 1080p 比较 Lanczos、Anime4K 与 QuickSR。QuickSR 若没有稳定
明显优势，停止当前模型，转向 Anime4K 或 SESR-M5/轻量动漫模型。之后才评估 cadence 与 VFI。

## 6. 会话与 worktree 安排

当前不需要再开多个实现会话：根因还没被 trace 分开，多会话只会同时猜参数和争用真机。

下一阶段按以下规则拆：

1. 当前主线先完成只读 trace 归因；真机一次只允许一个设备 owner。
2. 若 trace 指向 Media3 queue 或 AHardwareBuffer，才创建一个隔离 worktree 做对应实现；另一会话
   只读审查 trace/论文，不运行设备。
3. 模拟器硬件无关测试固定使用独立 `QuickSR_Isolated_API_35`；不使用其他任务的 emulator。
4. QNN/SurfaceFlinger 结论只来自显式序列号绑定的物理机；模拟器结果不能混入。
5. 子任务完成只回报证据与 patch，不自动等于合入、默认启用或发布。
6. 不 push；发布仍需单独授权。

## 7. 已完成验证与证据边界

- Python：54 项 PASS；
- Gradle unit、lint、debug APK、androidTest APK：PASS；
- 最终默认 APK SHA-256：
  `deb5ef03ce97588625232d56e585b2abdd3810e903db2da38df495b6293260c8`；
- 物理机 instrumentation：10 项登记，其中 8 项执行 PASS，2 项因未提供可选实验模型按假设跳过；
- QNN strict：HTP 配置与 CPU EP fallback 禁用已确认，per-node placement trace 未取得；
- SurfaceFlinger 是 actual-present 代理，不是光子时序；测试片源无音轨，A/V sync 未测；
- 证据只限一台物理机、当前 APK 与冻结片源，不外推到所有手机/动漫。

机器可读摘要：
[realtime-1080p-physical-20260905.json](evidence/realtime-1080p-physical-20260905.json)。
