# Project status

状态日期：2026-09-03

本文件区分源码实现、主机构建、真机执行、播放器代理性能、数值正确性、画质、热稳定性和人工审核。任何一项 PASS 都不能代替其他项。

## 一句话结论

v0.15.0 的播放器主链、Anime4K、默认关闭的 cadence 复用和 QNN postprocess overlap 均已有源码；一台 Android 16 / Adreno 740 已提供有界功能或 A/B 证据。Anime4K 仍缺同帧画质/GPU timing，QuickSR 1080p overlap 仍属 offline，VFI 仍只在独立 CLI 中，不能合并表述为产品级实时完成。

## 当前状态

| 能力或门禁 | 状态 | 已确认 | 尚未证明 |
| --- | --- | --- | --- |
| 主机构建 | PASS | v0.15.0；当前 105 个 Java 单测通过；最近一次完整门禁的 lint、x86_64/arm64-v8a assemble 均通过；arm64 APK 内的 Anime4K source asset 仍为 18,638 bytes 和固定 SHA-256 | 全新 checkout 仍需用户本地准备合法 QuickSR 模型与 vendor 依赖；主机构建不执行目标手机 GLES shader |
| 图片整图 2× | IMPLEMENTED | 系统选图、CPU/QNN HTP、tile/full-image、预览、取消和 PNG 保存路径已实现 | 本轮没有发布权利清晰的图片质量对比或新的数值验收 |
| Media3 播放器 | IMPLEMENTED / DEVICE SMOKE PASS | 本地视频、PlayerView、原画/GPU Lanczos/Anime4K x2 Small/QuickSR CPU/QuickSR QNN HTP 切换已实现；Anime4K 暂停、seek、恢复、HOME/resume 与三轮返回/重开无崩溃 | DRM、HDR、直播、字幕复杂场景和通用播放器插件不在当前范围 |
| 上一次视频 | PASS | 持久化 URI 权限、URI 和显示名；下次启动可一键重播 | 文档不保存私人 URI 或文件名 |
| QNN 运行时 | IMPLEMENTED | fixed-shape 模型 hash 校验、HTP tuning、graph finalization、持久 input/output tensor、资源释放和有限值抽查已实现 | 当前视频 smoke 没有附带发布级 placement/底层 HTP trace |
| 720p 神经输出 smoke | PASS（限定范围） | `640×360 → 1280×720`；645 帧 / 26.860 秒 = 24.0134 fps；四个稳定约 5 秒 MediaCodec 窗口均 Render=120、Drop=0 | 只是单设备、单 SDR 本地片源；不是 SurfaceFlinger latch、全量 A/V sync 或通用实时结论 |
| 长时探索运行 | OBSERVED | 同档位约 189.523 秒、4545 帧，折算约 23.981 fps，稳定窗口 Drop=0；电池温度代理约 39.5°C | 非标准功耗/温升基线，未形成频率、功耗、环境温度与 p95/p99 报告 |
| 数值正确性 | PASS（观察性张量范围） | 11 个权利清晰 clip 的 27 个合同选帧均用 Android 捕获的原始 NCHW 输入与 PC CPU ORT 对照；零失配、零非有限值，并生成 Lanczos 基线 | 不是 P4 real-image/SSIM、全帧/视频/显示画质或 per-node placement 证明 |
| 视觉质量 | OPEN（已有 PC 观察与主机门禁源码） | SHA-256 核验的 CC BY 3.0 动画与 CC BY 4.0 原生 4K 漫画/插画已形成 72 案例；干净输入 QuickSR 胜 30/36、模糊/JPEG Q35 仅胜 7/36、方形漫画胜 16/18；线稿/高低对比字幕/复杂 cadence 的 source-original 夹具和 fail-closed evaluator 已完成主机测试 | 仍缺更大规模代表性动漫集、LPIPS/感知指标、真实 Anime4K/cadence 输出、盲测与手机同帧输出；夹具测试 PASS 不是视觉质量 PASS |
| 可复用 AAR | NOT IMPLEMENTED | App 内部 effect/runtime 已形成 | 尚未拆成稳定 API、独立 library module 和兼容矩阵 |
| Source-only 发布 | PASS | publication gate 排除模型、APK、媒体、设备原始证据、绝对路径和凭据 | GitHub 仓库私有；不代表二进制或模型再分发获授权 |
| PC-first 动漫路线 | PASS（小型权利清晰语料限定） | 1.5×/2×/3×/4× checkpoint 已导出验证；三资产、两退化、匹配宽高比的 72 案例与 15 帧 H.264 路径已执行；方形素材不拉伸 | 语料仍非代表性日本商业动漫；合成退化不能覆盖所有编码、振铃、颗粒和字幕 |
| 动漫模型实验室 | SOURCE AUDIT PASS / PARTIAL DEVICE EVIDENCE | 11 个候选已有源码/权重/数据、I/O、参数/内存与 runtime 矩阵；恰好晋级 Anime4K x2 Small 与 SESR-M5 2x。Anime4K 随后已接入播放器并完成单设备三档 model-active 功能样本 | Anime4K 固定同帧、代表性动漫人工画质和 GPU timing 仍开放；SESR 尚未导出、做 CPU/QNN parity 或接 App；DIV2K 仅限学术研究，其他候选权利边界见独立报告 |
| Anime4K Android GPU | DEVICE FUNCTION PASS（单设备、有界） | Android 16 / Adreno 740 / GLES 3.2 直接报告 half-float/float color-buffer 扩展；720p/1080p/1440p 五段首帧均 model-active，未走两级 fallback；7.5 秒 24 fps clip 的 SF buffer 代理为 180/179/179，PSS 峰值 177,443-179,664 KiB，电池温度代理保持 38.9-39.0 C；固定源 pin、六案例主机同帧合同与 PSNR/SSIM/edge evaluator 已就绪 | 尚未取得真实 mpv/Android 配对输出；固定帧等价、字幕/线条人工质量、GPU timing、长时 thermal、p50/p95/p99、功耗、最终显示和其他设备仍未证明 |
| Anime cadence SR 复用 | IMPLEMENTED / DEVICE A/B（默认 OFF） | 只允许 benchmark Intent 开启；generation/stream epoch/缓存 ownership/最多连续 2 帧复用已测试。单设备 720p ON 共 680 帧，423 processed、257 reused，推理减少 37.79%；257 次均对齐冻结 hold map，映射 motion false reuse 为 0；混合一拍一/二/三、平移、嘴型/粒子、切换、淡变和字幕主机合同已生成并可检查错误/遗漏复用与 PTS/reference identity | 既有结果仍只是 effect output-submit 代理；新夹具尚未经过当前 analyzer/真机输出，低对比字幕、代表性动漫节奏、最终显示、人工画质、1080p 与长时热稳未证明，普通交互 UI 仍保持 OFF |
| QNN postprocess overlap | IMPLEMENTED / DEVICE A/B（默认 OFF） | 有界双阶段和额外单个输出张量已接线；720p 平均 23.573→23.711 fps（+0.6%），1080p 11.435→17.960 fps（+57.1%） | 1080p 仍属 offline，total p95 由 536.44 增至 625.37 ms，另增 23.73 MiB 张量；不是默认候选，最终显示/A-V/thermal 未证明 |
| Anime VFI 离线探针 | HOST + DEVICE RESIDENT MATRIX（单设备、有界） | RIFE v4.6 五档基线保留；IFRNet-S 同机三档完成但更慢，已停止。RIFE v4.25-lite 的 current ncnn 已解除 `MemoryData` 阻塞并完成三档真机矩阵：256x144 快 18.7%，但 160x90/320x180 慢 77.2%/56.0%，PSS 全档略高，已停止 | 全部仍为 offline-only，未进入播放器/APK。ANVIL 需 H.264 MV + SM8550/V73 专用 QNN runtime/context；权重再分发、代表性动漫时序质量、人工审核和长时热稳未证明 |
| Android 高分辨率档 | PASS（单设备功能限定） | 权利清晰子集在一台物理 Qualcomm 设备完成 1080p 主档、受门禁 1440p 实验档与 4K 显示回退档；4K 是 1080p neural→4K GL canvas | 未证明实时、热稳定、内存压力、最终显示、通用设备或原生 4K 神经输出 |
| x86_64 模拟器路径 | PASS（既有 v0.14.0 功能限定）/ v0.15.0 BUILD READY | Android Studio API 35 AVD 曾完成 720p/1080p/1440p/4K 首帧；本轮 x86_64 assemble 通过 | 本轮按设备占用约束未调用 adb、未安装 v0.15.0，也未让模拟器编译 Anime4K shader；模拟器 CPU/GPU 结果均不能外推目标手机 |
| 真机 QNN 自动矩阵 | PASS（单设备、离线范围） | 11/11 clip 的 720p 和 1080p 主链均功能 PASS、无报告级失败/设备错误；1440p 和 4K 显示回退在主档门禁后也功能 PASS；严格 QNN 会话配置、绑定收据、样本数和 p50/p95 已验证 | 不证明 realtime、thermal、per-node EP placement/fallback trace、屏幕 latch/A-V sync 或其他设备；详见 [移动子集验证](ANDROID_MOBILE_SUBSET_VALIDATION.md) |
| 实时流水线 P0 观测 | IMPLEMENTED / DEVICE EVIDENCE | raw ns 时间线、逐帧 generation/PTS/输入输出 CRC32、accepted/processed/late/dropped/bypassed/reused、worker queue depth、flush/seek proxy、p50/p95/p99/max 与 fail-closed 验证已实现，并已用于 overlap/cadence 真机 A/B；worker 队列固定为容量 2 的阻塞背压 | readback、GL submit、output-submit 与 PTS-wall drift 含明确代理，Media3 内部队列深度及 SurfaceFlinger/final display 未测；代理吞吐不能直接称为 input-to-photon |

## 已存档的 v0.12.0 物理机 720p smoke

```text
source:       1280x720 @ 23.976023 fps
backend:      QNN HTP
tuning:       Sustained
model shape:  640x360 -> 1280x720
frames / PTS: 645 / 26.860 s
derived rate: 24.0134 fps
codec:        4 stable windows, each about 120 rendered / 5 s, dropped 0
UI sample:    ORT/QNN run 9 ms, output conversion 10 ms, total 22 ms
```

这里的 `OrtSession.run` 是调用方 wall time，不是纯 NPU kernel 时间。UI timing 是定期抽到的一帧、整数毫秒，不是统计分布；`0 ms` 只表示取整后不足 1 ms。

## 已实现的数据流

```text
local SDR video
  -> Media3 / hardware MediaCodec decode
  -> Presentation establishes the selected neural-output canvas
  -> GL texture scaled to a static model input
  -> RGBA readback and RGB NCHW conversion
  -> persistent ORT input/output tensors
  -> CPU or QNN HTP QuickSRNetSmall selected scale
  -> RGB output conversion and GL upload
  -> neural RGBA written into the output-sized Media3 texture with original PTS
  -> SurfaceView
```

默认 profile 保持 16:9，避免早期方形 profile 对画面比例和有效像素利用率的不利影响。其他档位保留用于性能/质量对照。

## 当前性能判断

- 硬件解码器已经工作；为了速度重写整个播放器或 decoder 不是第一优先级。
- 当前 720p 档的单帧样本中，ORT/QNN run 约 9 ms，输出转换约 10 ms，后者已成为同量级热点。
- API 35 x86 CPU 首帧功能样本：1080p 总计 432 ms（ORT 116 ms、finite 扫描 172 ms），1440p 总计 651 ms（ORT 168 ms、finite 扫描 311 ms），4K 显示保底总计 476 ms。它们不是稳态统计，也不是手机 HTP 预测。
- v0.14.0 模拟器遥测回归在 1080p CPU 路径记录到 49 帧结构化样本；QNN benchmark 请求在配置阶段明确报告 runtime unavailable。该回归只验证自动化和 fail-closed 行为，不纳入 QNN 性能结论。
- 2026-09-02 权利清晰移动子集的无抓取重测：720p 的 11 条标准报告 measured frames 为 645–835（中位 685）、observed fps 为 22.41–28.34（中位 23.71）；1080p 为 295–335（中位 315）和 10.70–11.96（中位 11.69）。22/22 均功能 PASS，但均归类 `offline`。
- 同一轮中，1440p 实验档为 195 measured frames、7.71 fps，4K 显示回退为 305 measured frames、11.36 fps；两者功能 PASS、均 `offline`。4K 不是原生神经 4K。
- 1080p 的无抓取中位 queue/ORT/output-conversion/total p50 为 329/43/37/414 ms，p95 为 348/47/42/435 ms。它们是 effect-pipeline 样本，不是 NPU kernel、最终显示或端到端 A/V 延迟。
- 27 个合同选帧的 Android QNN→PC CPU 比较全部零失配、零非有限值，并各有主机 Lanczos 基线；比较只证明观察性张量一致性，不解冻 P4 或质量门禁。
- 后处理重叠的单变量 A/B 已完成：720p 受源 cadence 限制、收益约 +0.6%；1080p 代理吞吐提高约 57.1%，但仍只有 17.960 fps、p95 变差并增加 23.73 MiB 张量，因此默认仍是 SERIAL。下一项热路径变量是 JNI/NEON direct output packer。
- cadence-aware 720p A/B 实测减少 37.79% 推理，effect output-submit 代理由 23.717 提升至 24.069 fps；这只对冻结 15→24 重复映射有效，不能外推到复杂动漫语义或最终显示。
- QNN context cache 主要改善 session startup，不能解决稳态每帧 output conversion。
- C/C++/NEON、GPU compute shader、PBO 或 shared I/O 都是候选手段；只有逐段 profiler 显示收益后才应引入。
- PC CPU 的固定 640×360 模型 p50 为 1.5× 151.7 ms、2× 188.4 ms、3× 199.8 ms、4× 232.5 ms；它们均远低于 24/30 fps 实时预算。
- 72 案例 PC 语料链路中位 492.6 ms，最慢为 3440.2 ms。该结果说明高倍率级联适合离线导出，不应直接作为手机实时默认档。

## 仍然开放的门禁

1. 以 1080p SERIAL 为基线做 JNI/NEON direct output packer 单变量 ABBA，保持模型、tuning、队列、cadence 与片源不变；
2. Anime4K 获取确定 effect 输出的固定同帧参考，补 clean、退化、线稿、字幕边缘和人工盲审，不再依赖 OEM UI 截图；
3. cadence 增加混合一拍一/二/三、慢平移、嘴型、粒子、切镜、淡入淡出和低对比字幕的错误复用/漏复用审核；
4. 加入 SurfaceFlinger 或等价最终显示观测，并继续检查 A/V sync、seek、flush、pause/resume；
5. 只有候选先达到实时和质量门槛后，才建立环境温度、设备频率、功耗、内存和至少 10～30 分钟持续运行门限；
6. SESR-M5 先关闭导出和 CPU/QNN parity；若仍要 VFI，另立 ANVIL 系统任务，不再继续当前两个 ncnn 候选；
7. 若最终要复用，再拆分稳定 AAR API 和 demo app。

P0 观测字段、代理/未测边界和下一轮单变量 A/B 门禁见
[REALTIME_PIPELINE_TELEMETRY.md](REALTIME_PIPELINE_TELEMETRY.md)。

当前实现、进展、停止项和下一步先后顺序见 [IMPLEMENTATION_PLAN_AND_PROGRESS.md](IMPLEMENTATION_PLAN_AND_PROGRESS.md)。优化经验见 [REALTIME_VIDEO_SR_LESSONS.md](REALTIME_VIDEO_SR_LESSONS.md)，路线与门禁见 [PLAYER_ROADMAP.md](PLAYER_ROADMAP.md)，动漫模型/cadence/插帧研究与原始工作拆分见 [ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md](ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)，动漫模型实验室结果见 [ANIME_MODEL_LAB_REPORT.md](ANIME_MODEL_LAB_REPORT.md)，VFI 基线见 [ANIME_VFI_OFFLINE_EVALUATION.md](ANIME_VFI_OFFLINE_EVALUATION.md)，移动候选裁决见 [ANIME_VFI_MOBILE_CANDIDATE_PROBE.md](ANIME_VFI_MOBILE_CANDIDATE_PROBE.md)，发布规则见 [PUBLICATION_BOUNDARY.md](PUBLICATION_BOUNDARY.md)。
