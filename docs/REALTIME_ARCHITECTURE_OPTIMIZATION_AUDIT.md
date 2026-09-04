# QuickSR 1080p 实时架构优化审计

日期：2026-09-05

状态：物理机吞吐优化已经收敛；30fps 平均吞吐通过，严格最终显示节奏仍未稳定通过。

本文只处理第一硬门：`640x360 -> 1920x1080` QuickSR QNN 路径必须跟上原片帧率。
画质、换模型和插帧不抢占这个门。

## 1. 结论

“最高不到 20fps”的主要架构瓶颈已经被消掉。最终默认流水线在一台 Android 16 / SM8550
物理机、同一 30fps 权利清晰片源上得到：

- effect output-submit 吞吐 `30.0045 fps`，828 个稳态样本，源 PTS 覆盖率约 1；
- effect 计算导致的 `dropped=0`、`bypassed=0`，有界队列最大深度 2；
- QNN caller p50/p95 为 `27.884/30.736 ms`；
- output pack p50/p95 为 `11.321/15.503 ms`；
- SurfaceFlinger actual-present 代理中，原画 A1/A2 为 2/2 PASS，QNN B1/B2 为 1/2 PASS。

因此当前结论必须拆成两句：

1. **平均处理吞吐已经达到 30fps，不再是“算不过来”。**
2. **逐帧显示稳定性尚未保证。** QNN 的失败轮虽然平均为 `30.0341 fps`，仍出现一个
   `58.153 ms` 长间隔和两个补偿短间隔；不能写成“保证原片帧率”。

机器可读、去设备标识的摘要见
[realtime-1080p-physical-20260905.json](evidence/realtime-1080p-physical-20260905.json)。

## 2. 最终默认流水线

```text
MediaCodec / Media3 GL input
  -> 640x360 RGBA readback
  -> CPU RGB float32 NCHW input
  -> QNN HTP, pinned input + two pinned output tensors
  -> float32 NHWC 1920x1080 output
  -> deferred bulk copy on postprocess lane
  -> four fixed row stripes convert NHWC to RGBA8
  -> upload directly into Media3 output texture
  -> SurfaceFlinger
```

流水线并行关系：

```text
inference lane:   preprocess(N+1) -> QNN(N+1)
postprocess lane: copy/pack(N) -> output-ready
GL/Media3 lane:   direct upload/output-submit(N-1)
```

队列和 buffer 都有固定上界；frameId、generation 和 PTS 随 slot 走，flush/seek 后旧 generation
不能冒充新帧。这里的并行提高的是吞吐，不会把约 `179 ms` 的多帧管线 p95 延迟伪装成
单帧 33.3ms 延迟。

### `float NCHW/NHWC` 和往返是什么意思

- 输入 `float32 NCHW [1,3,360,640]` 约 2.64MiB：CPU 把 RGBA 像素拆成 R/G/B 三个平面。
- 旧输出 `float32 NCHW [1,3,1080,1920]` 约 23.73MiB：R/G/B 仍分平面，CPU 打包 RGBA
  时要跨三个大数组取值。
- 新输出仍是 float32、字节数不变，但改为 `NHWC [1,1080,1920,3]`：同一像素的 RGB
  连续排列，四条带可以顺序读取并行打包。
- 所谓 `GL -> CPU -> QNN -> CPU -> RGBA -> GL`，是纹理解码结果读回 CPU、整理为 QNN
  输入、QNN 输出回 CPU、打包为 RGBA8、再上传到 GL。当前没有声称这条链已经零复制。

## 3. 本轮实际消掉的工作

| 改动 | 物理机结果 | 决策 |
| --- | --- | --- |
| overlap 默认开启 | 把 QNN 与上一帧后处理重叠 | 保留 |
| float32 NHWC 输出 | 避免 1080p 三平面跨区读取 | 1080p 默认 |
| 四条带 Java pack | p95 约 15.5ms；两条带约 22.6ms | 四条带默认，两条带停止 |
| 首帧完整 finite scan | 移除每约 5 秒一次的 330～353ms 全张量扫描停顿 | 默认；首帧仍 fail closed |
| 双 pinned ORT 输出 + deferred copy | ABBA 中 inference caller p95 平均 `34.733 -> 30.529ms`，总提交 p95 `195.203 -> 178.195ms` | 默认；增加约 23.73MiB |
| 直接上传 Media3 output texture | 同尺寸档删除 private neural texture + scale blit；GL p95 曾 `6.435 -> 3.460ms` | 默认；4K canvas 保留缩放回退 |
| PBO 双缓冲上传 | GL 提交 p95 `3.460 -> 1.832ms`，但 SurfaceFlinger 仍 1/2 PASS，另增约 15.82MiB | 默认关闭，不能解决显示硬门 |

## 4. 已停止的方向

以下方向已有单变量结果，不再重复：

- JNI direct `FloatBuffer -> native packer`：约 116.5ms，远慢于 NHWC 四条带 Java pack；
- 两条带 pack：打包尾延迟更差，SurfaceFlinger 无改善；
- 只扩大队列：会增加延迟和内存，不能增加 HTP/CPU/GL 的真实服务率；
- 同一 ORT/QNN session 上多加 inference worker：session/graph 仍串行，且会破坏现有所有权合同；
- PBO：只改善 CPU 侧 GL 提交代理，没有提高严格显示通过率；
- temporal batch=2：增加收集延迟和输出内存，主机方向收益不足以解释显示抖动；
- native/NEON 旧 packer：既有真机 ABBA 显著回归，不重开。

这些候选保留为可审计负结果，而不是下一轮继续排列组合。

## 5. 当前主要矛盾

最终版 30fps 分段 p95：

| 阶段 | p95 |
| --- | ---: |
| QNN caller | 30.736ms |
| tensor output copy | 约 3～4ms |
| NHWC -> RGBA pack | 15.503ms |
| GL upload submit 代理 | 6.987ms |
| output-ready 等待 Media3 GL submit | 95.201ms |
| effect accepted -> output-submit 代理 | 179.024ms |

因为 inference 与 pack 已重叠，单帧各段相加不能用来推导吞吐。现在平均服务率已经达到
30fps，而 SurfaceFlinger 偶发长/短补偿间隔仍存在，主要矛盾从“算力不足”变为：

> Media3 固定内部队列、GL fence/调度、SurfaceFlinger latch 与系统调度共同造成的尾部节奏抖动。

这是一项基于现有分段与 ABBA 的工程推断，不是已经定位到某一行代码。尤其
`outputReadyToGlSubmitQueueNs` 是应用侧代理，不能单独证明 Media3 内部是根因。

## 6. 下一步只做能回答主矛盾的实验

### P1：一次 Perfetto/FrameTimeline 定位，不继续盲调参数

在失败窗口同时采集 app worker 调度、RenderThread/GL fence、BufferQueue、SurfaceFlinger
FrameTimeline 和 CPU frequency。目标是把 58ms 长帧归到以下唯一一类：

1. QNN/CPU 结果未按时 ready；
2. Media3 GL 队列或 fence 未按时提交；
3. BufferQueue/SurfaceFlinger latch 错过一个 vsync；
4. OS 调度或降频抢占。

没有这份关联 trace，不再尝试第五种 buffer/线程组合。

### P2：按 P1 结果选择一个实现

- 若结果 ready 晚：为 postprocess/GL 使用有界 deadline scheduling，或把最终 RGBA pack/upload
  移到 native/GPU shared buffer；必须保持逐 PTS 输出。
- 若 GL fence 晚：实现 AHardwareBuffer/EGLImage 或同一 GL context 的 texture-resident 输出，
  目标是消掉最后一次 CPU RGBA upload；先做最小 native proof，再接播放器。
- 若 Media3 队列/latch 晚：对固定 queue capacity 做可回滚的本地 fork 单变量，不与模型或
  pack 改动混测。
- 若系统抢占/降频：固定性能模式并记录频率/thermal；若仍无法稳定，当前设备上的 QuickSR
  实时路线应停止，而不是继续加缓存掩盖。

### P3：显示节奏通过后的工作

只有 QNN SurfaceFlinger ABBA 两轮均无长/短补偿间隔，才继续有音频片源 A/V sync、
10～30 分钟 thermal，以及代表性动漫画质。cadence reuse、换轻模型和 VFI 都是后续分支，
不能替代当前逐帧显示门。

## 7. 证据边界

- SurfaceFlinger `--latency` 是 layer actual-present 代理，不是光子时序，也不包含 A/V sync。
- strict QNN 证据确认 HTP 配置和 CPU EP fallback 禁用，但尚无 per-node placement trace。
- 30fps 片源无音轨，因此本轮没有 A/V sync 结论。
- 所有结论仅限一台设备、一个测试片源和当前 APK hash，不能外推到所有手机或动漫。
- 隔离 AVD `QuickSR_Isolated_API_35` 只用于硬件无关检查；既有 emulator 和电视均未参与。
