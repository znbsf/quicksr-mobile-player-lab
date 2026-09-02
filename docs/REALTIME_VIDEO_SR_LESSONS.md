# 实时视频超分：优化经验与教训

日期：2026-09-03

对象：QuickSRNetSmall 2×、ONNX Runtime QNN EP、Qualcomm HTP、Media3 1.11.0

当前结果：在指定设备与 `1280×720 @ 23.976023 fps` 本地 SDR 片源上，默认 `640×360 → 1280×720` 神经档跟住源帧率。

2026-09-03 源码进展：P0 已补 raw ns、逐帧 identity/hash、generation/flush 隔离、
有界 worker 队列和 fail-closed 主机验证器；尚未进行新一轮物理设备运行或吞吐 A/B。
公开 API 不能直接给出的 readback、GPU completion、SurfaceFlinger latch 和最终显示仍分别
标为 proxy 或 unmeasured。完整合同见
[REALTIME_PIPELINE_TELEMETRY.md](REALTIME_PIPELINE_TELEMETRY.md)。

## 1. 最重要的结论

慢不等于 NPU 算力不够，也不等于播放器必须改写为 C。

本项目中，播放器已经使用 Qualcomm 硬件 MediaCodec 解码。早期版本的主要问题出现在神经 effect 热路径：每帧对象/缓冲区生命周期、输出提取、CPU/GL/ORT/QNN 之间的数据边界以及由此产生的排队。保留 Media3、针对热路径做静态 shape、持久 I/O 和 provider tuning 后，连更大的 `640×360 → 1280×720` 档也能在指定 23.976 fps workload 上保持 decoder-renderer 代理实时。

通用顺序应是：**先测每一段，再减少跨域和分配，最后才考虑更换语言或整个播放器。**

## 2. 最终可复算结果

最终 v0.12.0 APK smoke：

| 指标 | 观察值 |
| --- | --- |
| 解码视频 | `1280×720 @ 23.976023 fps` |
| 神经 shape | `640×360 → 1280×720` |
| 后端 | QNN HTP Sustained |
| 完成帧 / PTS | `645 / 26.860 s` |
| 完成帧折算速率 | `24.0134 fps` |
| MediaCodec 稳定窗口 | 4 × 约 5 秒；每窗 Render=120、Drop=0 |
| 单次 UI 抽样 | input copy 0 ms；ORT/QNN run 9 ms；output copy 0 ms；output conversion 10 ms；total 22 ms |

另一次同档探索长跑观察到 `4545 / 189.523 s = 23.981 fps`，稳定窗口 Drop=0，电池温度代理约 39.5°C。它说明短期可持续性良好，但不替代标准热/功耗测试。

早期与中间探索还观察到 `256×144 → 512×288`、`256×256 → 512×512`、`512×288 → 1024×576` 和 `512×512 → 1024×1024` 在预热后接近源 24 fps。它们是同一实验方向的性能线索，不应混成跨设备基准榜。

## 3. 什么真正起作用

### 3.1 静态矩形 shape

视频的主档位改成与源内容一致的 16:9：

```text
256x144 -> 512x288
512x288 -> 1024x576
640x360 -> 1280x720
```

这样既避免方形模型拉伸画面，也避免把大量算力花在最终会被裁剪或重新缩放的像素上。QNN 也更适合提前冻结、编译和复用静态图。

### 3.2 持久 input/output tensor

Session 生命周期内创建一次 direct `FloatBuffer` 和 `OnnxTensor`，每帧复用；输出通过 ORT pinned-output API 写入固定 tensor。这样减少每帧 tensor/result materialization、shape 检查和大数组分配。

但必须保留证据边界：本轮同时修改了静态模型、QNN tuning、graph finalization、buffer 复用和 finite scan 频率，没有同一 APK 的 unpinned/pinned ABBA。因此不能把全部提升都归因于 pinned output 单项。

### 3.3 QNN 持续性能配置

连续播放使用：

- `qnn.perf_mode=sustained_high_performance`；
- `qnn.rpc_control_latency=100`；
- tuned profile 使用 HTP graph finalization optimization mode 3。

Burst 保留给短时低延迟实验，Baseline 保留做对照。配置标签只代表请求了该选项；是否实际生效仍应由 QNN profiling/trace 验证。

### 3.4 把完整 pipeline 分段计时

UI 与内部统计区分：

```text
session setup
copy/readback
executor queue
RGBA -> RGB/NCHW
OrtSession.run
pinned output copy
finite scan
RGB/NCHW -> RGBA
GL upload / effect total
```

这避免把 `OrtSession.run` 错当成整帧延迟，也能看到优化后瓶颈已经移动：最终 720p 抽样中 run 约 9 ms，而输出转换约 10 ms。

### 3.5 低频完整有限值扫描

每帧扫描 276 万个 720p float 输出会浪费 CPU 和内存带宽。当前在第一帧及每 120 帧做完整 finite scan，其他帧仍检查 buffer shape/长度。生产方案还应加入更轻量的逐帧 sentinel/hash 或抽样，不能只依赖低频 full scan。

### 3.6 保留成熟播放器栈

Media3 提供硬件解码、PTS、seek、Surface 生命周期和控制器。只要 profiler 没证明这些层是瓶颈，替换成 C 播放器会扩大风险面，却不会自动消除 GL readback、tensor conversion 或 NPU output upload。

## 4. 没有解决、或容易误判的地方

### 4.1 `OrtSession.run` 不是纯 NPU kernel

它是 Java 调用方看到的 wall time，可能包含 JNI、QNN EP、RPC、图执行和 provider 内部传输。要回答“纯 NPU 多快”，必须使用可绑定到同一运行的 QNN/HTP profiling。

### 4.2 Render=120、Drop=0 不是最终屏幕 24 fps

MediaCodec 的 Render/Drop 是 decoder-renderer 代理。它不能证明 SurfaceFlinger 最终 latch、屏幕刷新、A/V sync 或端到端输入到光子的延迟。公开结论必须写成“指定 workload 的播放器代理跟住源帧率”。

### 4.3 单帧 UI 数字不是分位数

UI 每隔若干帧展示当前一帧，且毫秒值向下取整。`0 ms` 表示 `<1 ms`，不是零成本。正式报告需要保存 raw nanoseconds 并输出 p50/p95/p99/max。

### 4.4 排队可以把吞吐问题伪装成延迟

如果允许 executor 无限排队，帧最终都“处理完成”也可能已经落后播放时钟。必须同时记录 accepted、processed、late、dropped、bypassed、queue depth、最大等待和 PTS-wall-clock drift，并采用有界 in-flight 策略。

### 4.5 Context cache 只主要解决启动

QNN context cache 可以减少 session 初始化和图编译时间，但不会直接消除稳态每帧 RGB/RGBA 转换和 GL upload。优化目标要分清 cold start 与 steady state。

### 4.6 实时不等于画质提升

当前证据说明 throughput 候选可用，不说明 QuickSR 相对 Lanczos 在该视频上更清晰。画质需要权利清晰的 HR reference、确定性降采样、PSNR/SSIM/感知指标和人工盲测；模型输出的 temporal stability 也要单独评估。

## 5. 是否把中间流程都换成 C

可以把热点逐步下沉到 C/C++，但不是“能换就全换”。决策表如下：

| 热点 | 首选尝试 | 何时值得 C/C++ |
| --- | --- | --- |
| MediaCodec 解码 | 保留硬件解码 | 只有 codec/renderer trace 证明它是瓶颈 |
| RGBA → NCHW | buffer 复用、SIMD/compute shader | Java 循环在 p95 中明显占主导时，用 NEON 或 GPU shader |
| ORT/QNN 调用 | 保留官方 API、pinned I/O | 需要 QNN native custom I/O、shared buffer 或 Java API 无法表达的零拷贝时 |
| NCHW → RGBA | 先优化布局和一次遍历 | 当前 720p 已与 ORT run 同量级，适合 NEON/GPU compute A/B |
| GL upload | PBO、双/三缓冲、fence | CPU staging/upload 明确占主导，且能维护严格资源所有权时 |
| 播放器控制层 | 保留 Media3 | 只有 Media3 API 限制阻断目标功能，而非单纯为了“C 更快” |

换成 native 后仍然跨 GPU↔CPU↔NPU，性能不会凭语言自动出现。真正通用的目标是减少格式转换、内存复制、同步点和动态分配。

## 6. 下一轮最有价值的优化

1. 给每一阶段保存 raw ns 样本，报告 p50/p95/p99/max；
2. 增加 accepted/processed/late/drop/bypass/queue-depth 与 PTS-wall-clock drift；
3. 在完全相同的 APK/tuning/shape 上做 pinned 与 unpinned ABBA；
4. 交替输入 A/B，逐帧记录输出 hash，并与 CPU/golden 比较，排除 stale output；
5. A/B 测试 Java 输出转换、C++ NEON 与 GPU compute shader；
6. 评估 uint8/NHWC 或量化 I/O，减少 720p float output 的内存流量；
7. 只有在数据证明有收益时，再评估 QNN shared allocator、native I/O 或更深的 zero-copy；
8. 完成 10～30 分钟温度、频率、功耗、A/V sync 和最终显示帧率测试；
9. 建立 Lanczos、QuickSR Small/Medium 或其他模型的同片源质量/性能矩阵。

## 7. 可复用的通用方法

```text
冻结 workload 与目标帧预算
  -> 测 decode / readback / preprocess / inference / postprocess / upload / render
  -> 找最大 wall-time 与最大数据搬运
  -> 静态 shape + 持久 buffer/tensor + 有界队列
  -> 单变量 ABBA 验证
  -> correctness / stale-output / PTS / drop 回归
  -> p95 sustained + thermal gate
  -> 再决定 Java、C++、GPU shader、模型变体或播放器替换
```

这个顺序比先选“Java 还是 C”更通用：它把性能问题还原为可测的计算、搬运、同步和排队问题。
