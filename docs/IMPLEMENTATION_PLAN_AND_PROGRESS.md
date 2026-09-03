# 动漫视频增强：实现计划与进展

状态日期：2026-09-03

盘点基线：`main` 的文档前一提交 `26052cc`

文档角色：当前实现、证据等级、先后依赖和下一步的唯一总览。早期研究理由仍保留在
[动漫视频实时超分与插帧执行拆分](ANIME_VIDEO_SR_RESEARCH_AND_EXECUTION_PLAN.md)，各项原始测量以链接的专项报告为准。

## 1. 当前结论

播放器主链已经能用：Media3/MediaCodec 解码、本地视频播放，以及原画、GPU Lanczos、
GPU-resident Anime4K x2 Small、QuickSR CPU 和 QuickSR QNN HTP 切换均已接入 App。
这不等于所有增强模式都达到产品级实时和画质门槛。

当前四条优化线的裁决如下：

1. **Anime4K 已进入可选择播放器路径。** 单台 Android 16 / Adreno 740 已完成
   720p、1080p、1440p 的有界 model-active 功能运行；固定同帧参考、GPU timing、
   代表性动漫线条/字幕画质和长时热稳仍未关闭。
2. **动漫 cadence 复用已接入 QuickSR 热路径，但只允许 benchmark Intent 开启，默认和交互模式均为 `OFF`。**
   单台真机 720p A/B 中，680 帧有 257 帧复用，实测减少 37.79% 推理；它不插帧、不改 PTS，
   也不是固定“每三帧一次”。低对比字幕和代表性动漫人工画质仍是开放门禁。
3. **QNN inference/postprocess 重叠已实现为默认关闭的构建实验。** 720p 已受源帧率限制，
   平均代理吞吐仅提高约 0.6%；1080p 从 11.435 提高到 17.960 fps，但仍属 `offline`，
   p95 尾部变差且增加 23.73 MiB 输出张量，因此不能改成默认路径。
4. **插帧仍未进入播放器。** RIFE v4.6 和 IFRNet-S 只存在于独立 native CLI/离线评测；
   IFRNet-S 已停止。RIFE v4.25-lite 已通过新版 ncnn Windows host gate 并完成 Android
   arm64 构建，但尚未在手机执行。

本次盘点时 `adb devices -l` 未枚举到设备，因此没有把 RIFE v4.25-lite 的 Android 构建
误写成真机兼容或性能结果。设备重新连接后，最先执行的就是该候选的三档常驻矩阵。

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
- `app/build.gradle.kts`：`quickSrPostprocessOverlap` 默认 `false`；
- `vfi-benchmark/`：prefilter、host/device CLI 构建、常驻矩阵和候选证据；没有 App 接线。

## 4. 实现与证据进展

| 工作项 | 源码状态 | 当前最高证据 | 决策 |
| --- | --- | --- | --- |
| Media3 播放器与 QuickSR CPU/QNN | `IMPLEMENTED` | 单设备 720p/1080p/1440p/4K-display 功能矩阵 | 保留；实时、最终显示和长时热稳分开判断 |
| 逐帧 telemetry、generation、PTS、CRC、队列和生命周期 | `IMPLEMENTED` | 已用于 overlap/cadence 真机报告 | 继续作为所有热路径 A/B 的共同合同 |
| QNN postprocess overlap | `IMPLEMENTED`，默认 OFF | `DEVICE_BOUNDED`：1080p +57.1% 代理吞吐，但仍离线且尾部回归 | 保留实验，不设默认；下一变量是 native direct packer |
| Anime4K x2 Small | `IMPLEMENTED`，UI 可选 | `DEVICE_BOUNDED`：三档 model-active，短片无 fallback | 先补固定同帧和 GPU timing，不急着换 Medium |
| cadence-aware SR reuse | `IMPLEMENTED`，仅 benchmark 可开 | `DEVICE_BOUNDED`：720p 减少 37.79% 推理，映射 motion false reuse 为 0 | 继续画质/复杂 cadence 门禁；暂不进普通 UI |
| 动漫 SISR 候选筛选 | 工具与清单完成 | Anime4K 已进入设备门禁；SESR-M5 仅有来源/接口方案 | SESR 先做导出与 CPU/QNN 一致性，不直接接播放器 |
| RIFE v4.6 | 独立 CLI | `OFFLINE_ONLY`：五档单设备 resident 矩阵 | 作为冻结基线，不宣称播放器实时 |
| IFRNet-S | 独立 CLI | `OFFLINE_ONLY`：PSS 低约一半但三档更慢 | `STOPPED` |
| RIFE v4.25-lite | host/Android CLI 已构建 | host 确定性 PASS；Android 仅编译，未执行 | `PENDING`：设备回来后跑三档矩阵 |
| ANVIL | 来源和架构筛选完成 | 论文/源码表明需 H.264 motion vector + Vulkan + QNN | 暂不实现；若继续须另建 SM8550/V73 系统任务 |
| 播放器内 VFI | 未实现 | 无 | 在 kernel、动漫画质、许可、A/V sync 和热预算过门前不创建 |

专项证据：

- [QNN 后处理重叠 A/B](ANDROID_QNN_POSTPROCESS_OVERLAP_AB.md)
- [动漫 cadence 复用](ANIME_CADENCE_REUSE.md)
- [Anime4K Android GPU 集成](ANIME4K_ANDROID_GPU_INTEGRATION.md)
- [动漫 VFI 离线基线](ANIME_VFI_OFFLINE_EVALUATION.md)
- [移动 VFI 候选裁决](ANIME_VFI_MOBILE_CANDIDATE_PROBE.md)
- [RIFE v4.25-lite 新版运行时探针](ANIME_VFI_RIFE_V425_LITE_PROBE.md)

## 5. 下一步执行顺序

```text
P0 设备重新连接
  -> RIFE v4.25-lite 三档 resident matrix
  -> 与冻结 RIFE v4.6 同档比较并作 stop/continue 裁决

P0 完成后可并行
  |-- P1A QuickSR：serial baseline -> JNI/NEON direct packer 单变量 A/B
  `-- P1B 画质：Anime4K 固定同帧 + cadence 混合节奏/字幕/平移审核

P1 共同检查点
  -> 性能、数值、画质、生命周期均过门：再讨论实验 UI 和长时热稳
  -> 任一核心门禁失败：保留离线/默认关闭，不靠扩大队列掩盖问题

条件性后续
  |-- SESR-M5：只在权利边界允许时做导出、CPU ORT、QNN parity
  `-- ANVIL：只在仍明确需要手机 VFI 时建立 H.264 MV + SM8550/V73 专项
```

### P0：完成 RIFE v4.25-lite 真机裁决

前置条件是 `adb` 只枚举一台已授权的 arm64 物理设备，并确认设备/驱动与冻结 RIFE v4.6
比较口径兼容。只运行已准备的 `160x90`、`256x144`、`320x180` 三档，使用该模型真实的
128 像素 padding；延迟进程和 PSS 采样进程继续分离。

完成条件：

1. 输入文件、manifest、prefilter 决策和设备侧 hash 全部重新核验；
2. 每档完整返回所有 task timing、确定性输出 hash、median/min/max、PSS/RSS 和短时温度代理；
3. 与 RIFE v4.6 同档比较，不把 host fresh-process 时间与 Android kernel 时间混算；
4. 结果仍标为 `OFFLINE_ONLY`，不在同一改动里接播放器。

停止条件：Android 不兼容、任一证据绑定失败，或三档相对 v4.6 没有可解释的速度/质量收益。
后一种情况直接结束 ncnn RIFE 替换线，不为了“已经做了很多”继续集成。

### P1A：QuickSR native direct packer

以默认 SERIAL 1080p 为基线，只替换 float NCHW 到可上传 RGBA direct buffer 的 pack/copy
实现；模型、QNN tuning、队列、输入片源、cadence 和 profile 全部固定。先做 Java/native
逐帧 hash 与边界尺寸测试，再做至少 ABBA 真机比较。重点看 output-pack p50/p95、总计、
吞吐、PSS、drop/bypass、generation/seek 和释放行为。

只有在数值一致、p95 不恶化且有可重复净收益时，才讨论默认化；否则保留 Java 路径。

### P1B：代表性画质与 cadence 安全性

Anime4K 不再依赖 OEM 截图或 UI 位置。应从确定的 effect 输出或受控离屏 GL fixture 获取
同帧，和固定 mpv/上游参考比较 clean、blur/JPEG、线稿、字幕边缘，再进行盲审。

cadence 需要增加一拍一/二/三混合、慢速平移、嘴型、粒子、硬切、淡入淡出、高/低对比字幕
夹具，检查错误复用率、漏复用率、PTS、reference identity 和人工节奏感。已通过的 BBB
15→24 重复映射只证明当前片源上的 hold 判断，不能代替动漫语义质量。

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

1. **现在不再新增并行工作树。** 先在主线完成 P0 真机矩阵；这一步使用已准备的离线 runner，
   不需要另开播放器修改线。
2. P0 裁决后，若两项都继续，再开两个互不重叠的工作树：
   `native-output-packer` 只拥有 App/native build 和对应测试；`visual-quality-gates` 只拥有
   fixture、捕获/比较工具和报告。
3. SESR 或 ANVIL 一次只启动一个。二者会引入新的 runtime、模型和许可证据，不与播放器
   热路径实验混在同一分支。
4. 各任务完成后先回报结果，再由主线审查 patch equivalence、测试、证据边界和 source-only
   gate。工作树完成不自动等于合入、默认启用或发布。
5. 不自动 push；远端仍落后于本地 `main`，发布需要单独授权。

## 7. 本次盘点验证

- `:app:testDebugUnitTest`：105/105 PASS；
- Python：`scripts` 40/40、`golden-correctness` 14/14、`pc-benchmark` 22/22、
  `derived-models` 19/19、`vfi-benchmark` 12/12，共 107/107 PASS；
- `adb devices -l`：本次没有枚举到设备，因此未执行或声称新的真机结果；
- 所有性能数字继续采用专项报告中的既有、绑定证据；本次没有从构建成功推断运行成功。

## 8. 计划更新规则

每完成一个阶段，只更新四件事：状态标签、实际证据、裁决和下一依赖。不得用“代码已写”、
“shader 编译”或“CLI 能启动”替换真机执行、最终显示、画质、许可或长时热稳。负结果和停止
路线保留在表中，避免后续会话重复走同一条路。
