# QuickSR 1080p 实时架构优化审计

日期：2026-09-04

状态：主机与 x86_64 模拟器候选探针完成；单线程 alpha 索引优化已接现有 Java 路径；
物理机 QNN 裁决待执行。

本文只回答一个问题：在不先替换 QuickSR 权重的前提下，现有架构还有没有机会把
`640x360 -> 1920x1080` 从 17.960 fps 提高到冻结片源的 23.976 fps，并按每个源 PTS
交付。画质、插帧和新模型均不抢占这条硬门。

## 1. 结论先行

还有架构优化空间，但“再加线程/再加队列”不是答案。现有 overlap 已是两阶段流水线：
QNN 处理 frame N+1 时，CPU 打包 frame N。其理想吞吐由较慢阶段决定，而不是两者相加。
1080p 两次真机 overlap 的 QNN caller p50 约 `40.90/40.98 ms`，output pack p50 约
`36.43/41.92 ms`；按 `max(stage)` 算出的理想中位上限也只在 23.85～24.45 fps 附近，
而 p95 单阶段约 49.5～49.9 ms。真实代理只有 17.960 fps，说明调度、尾延迟及未完整计时
的 readback/upload 仍会吃掉余量。

当前主要矛盾因此进一步收敛为：

1. 先消灭 23.73 MiB 的 float NCHW 输出及 1080p CPU pack，而不是继续优化同一个循环；
2. 同时把 QNN caller 的平均值和尾部压到 41.708 ms 帧预算以内；
3. 若 QNN 单 graph 仍跨预算，只有真正的多 graph 并发或同权重 GPU-resident 路线可能继续，
   排队、普通线程池和更大的 buffer 只会隐藏积压。

本轮优先候选是“同权重图直接输出 `uint8 NHWC RGB` + `GL_RGB8` 纹理”。它把公开输出
从 24,883,200 bytes 降到 6,220,800 bytes，主机逐值比较零失配；模拟器也验证了 RGB8
上传、FBO 读取和 alpha=255 的 GL 合同。这不是 QNN 性能证明，下一证据必须是真机编译、
节点 placement 和固定片源 A/B。

## 2. 为什么普通流水线仍不够

当前及候选结构可写为：

```text
现状 SERIAL
  readback/input -> preprocess -> QNN -> float copy -> CPU RGBA pack -> GL upload

已实现 OVERLAP
  inference lane:    preprocess(N+1) -> QNN(N+1) -> float copy(N+1)
  postprocess lane:                       CPU pack(N) -> GL upload(N)

目标有界流水线
  input lane:        slot(N+1): readback -> preprocess
  inference lane:    slot(N):   QNN，单 graph 严格串行
  display lane:      slot(N-1): RGB8 upload/composite
```

目标流水线只允许 2～3 个固定 slot，每个 slot 绑定 PTS、generation、输入/输出 buffer 和
完成 fence。seek/flush 必须使旧 generation 失效，不能靠无限队列制造“看起来一直在跑”。

同一个 QNN graph 的异步 execute 仍进入同一 graph 队列，不能据此声称帧间并行；QNN 的
公开 API 也说明 asynchronous execute 返回后请求被排队。只有独立 graph/session 才值得做
并发 A/B，但会争用相同 HTP 资源并增加内存，必须由 optrace/利用率决定，而不是默认开启。
[QNN graph execute async 文档](https://docs.qualcomm.com/doc/80-63442-10/topic/function_QnnGraph_8h_1a3ea05f42a9295f9a74a2e3a0cdd64228.html)

## 3. 本轮实际探针

机器可读摘要见
[realtime-architecture-probes-20260904.json](evidence/realtime-architecture-probes-20260904.json)。
生成的 ONNX 均位于忽略的 `build/experiments/`，没有替换 App 当前模型。

| 候选 | 实际结果 | 裁决 |
| --- | --- | --- |
| 图内输出 `uint8 NHWC RGB` | 同权重、同卷积；主机与旧 half-up pack 逐值零失配；边界字节 -75%；CPU p50 60.651→81.158 ms | **最高优先级真机候选**；CPU 增时说明附加 op 不能假设免费，必须核验 QNN placement/fusion |
| `GL_RGB8` 纹理 | GLES3 x86_64 模拟器上传后 RGB 逐字节一致，FBO 读回 alpha=255 | **接口可行**；App 接线仍需一个 texture-to-output 合成 pass |
| heap 四条带并行 pack | 多轮均数值一致；最新模拟器 p50 31.78→10.06 ms，但跨进程有时反向变慢 | **过渡备选**；只允许默认关闭，真机单变量 A/B 后决定 |
| 预计算 alpha 索引 | 保持任意 alpha 逐字节一致；新独立 AVD 三次专项 ABBA 的单线程方向收益为 11.87%、24.57%、19.50%；四条带有时反向变慢 | **已接现有单线程 Java 路径**；避免每帧约 207 万次 `x * in/out` 整除，真机收益待测 |
| 不透明 RGBA/RGB 三通道 | 不透明 RGBA 最新单线程/四条带 21.61/6.66 ms；RGB 单线程/四条带 23.79/13.07 ms，上传量 7.91→5.93 MiB | 视频 alpha 合同确认后可用；最终与图内 RGB8 合并 |
| Java direct `IntBuffer` 写入 | 最新 103.30 ms，明显慢于 heap 路线 | **停止** |
| Java direct `FloatBuffer` 逐元素读取 | 最新单线程/四线程 208.28/57.96 ms | **停止**；减少一次 copy 必须在 native/runtime/图边界完成 |
| temporal batch=2 | 三次重复 CPU 吞吐收益 4.205%～16.864%，零数值误差 | **降级**；不足 33.5% 缺口，且增加至少一帧等待和 47.46 MiB 输出 |
| 同帧左右分块 batch=2 | 4 输入像素 halo，拼接零误差；三次 -2.312%～+0.738%，额外输入 1.25% | **不作为 batch 优化**；仅保留为将来双 graph 正确性基础 |
| CRC/finite 全帧扫描 | 最新模拟器 output CRC 2.21～2.29 ms；float finite scan 108.87 ms | 产品路径只做抽样；严格 benchmark 仍保留完整证据，不用调低验证强度伪造结果 |

最终模拟器证据来自新开的 `Medium_Phone_API_35` / x86_64 独立实例，所有命令显式绑定新
serial；没有继续占用此前正在工作的 AVD。整套 instrumentation 为 7/7 PASS，只证明
Java/GLES 数值、buffer ownership 和调度候选。

此外，独立实例用生成的 `640x360 @ 24000/1001` H.264 片源实际走通三轮
Media3→QuickSR CPU→GL，顺序为 serial A1、overlap B、serial A2。稳态代理分别是
9.699、8.699、6.451 fps；同时 ORT p50 从 59/98/80 ms、output pack p50 从
25.664/46.588/48.177 ms 漂移。A2 在 accepted=209 前无 drop，之后的 drop 出现在循环片源的
end-of-stream/flush generation 切换及 Activity 销毁；release 最终清空队列。由于 A2 没有恢复
A1，宿主负载/JIT/双模拟器争用漂移已经大于候选差异，故 **A/B 性能裁决为不确定**，不能据此
认定 overlap 更快或更慢。它只证明实际播放接线、队列上界和释放路径；仍是 CPU 模拟器
`OFFLINE`，不是 QNN HTP、最终显示或目标帧率证明。当前没有物理 Qualcomm 设备，因此本文
没有新增 HTP 速度、功耗或热稳证据。

## 4. 除流水线外的完整候选集

### P1：减少表示和跨域搬运

1. **显示友好图输出。** 保留卷积权重，图末执行 clamp/scale/cast/transpose，直接得到
   `uint8 NHWC RGB`。若 QNN 可全图放置，它同时消除 QNN output→Java float[]、CPU NCHW
   pack 和 alpha 搬运。这是本轮唯一同时触及 23.73 MiB 输出和 36～42 ms pack 的候选。
2. **QNN shared buffer/native I/O binding。** Qualcomm 的 shared-buffer 路径可注册共享内存
   并以 memhandle 交给 graph；ONNX Runtime QNN 也已有共享 allocator 工作，但 Java API
   是否能绑定 provider-owned buffer 必须以实际版本验证，不能把它写成 GL→QNN 零复制。
   [Qualcomm shared-buffer 教程](https://docs.qualcomm.com/doc/80-63442-10/topic/htp_shared_buffer_tutorial.html)、
   [ONNX Runtime QNN shared allocator PR](https://github.com/microsoft/onnxruntime/pull/23136)
3. **输入侧融合。** 输入只有 640x360，当前模拟器 preprocess p50 约 2.74 ms，优先级低于
   输出。但在输出问题关闭后，可把 RGBA→RGB normalize/transpose 移入图或 GPU shader，
   再尝试 AHardwareBuffer/rpcmem 绑定。

### P2：更换执行域，但不更换权重

1. **同一 QuickSR ONNX/权重改跑 GPU-resident kernel。** ncnn Vulkan 支持 device-side
   extractor/command chaining，并有 Android HardwareBuffer 零复制输入接口。它可能避开
   GL→CPU→QNN→CPU→GL，但 Media3 的 GL texture 与 Vulkan/AHB 之间仍需自定义互操作和
   fence；官方能力不是本 App 已零复制的证明。
   [ncnn Vulkan notes](https://github.com/Tencent/ncnn/wiki/vulkan-notes)、
   [ncnn Android HardwareBuffer](https://github.com/Tencent/ncnn/wiki/use-ncnn-with-android-hardware-buffer)
2. **双 QNN graph/session。** 仅当 detailed profile 显示单 graph 没吃满 HTP、并且两路
   同时执行确有吞吐提升时继续。当前进程级 session lock 和单 session 设计使“多开 Java
   worker”没有意义；双 graph 还必须量 PSS、热和 p95。
3. **DepthToSpace 移到 GL。** 前一层为 `[1,27,360,640]`，元素数与 RGB 1080p 完全相同；
   单纯移动 PixelShuffle 不减少传输字节，只在 optrace 证明 QNN 末端 layout/DepthToSpace
   很贵时才值得写 shader，当前不是主线。

### P3：利用内容冗余，但不冒充逐帧满模型

1. 已有 cadence reuse 可以让 held source frame 复用已增强像素，同时仍为每个原 PTS 输出；
   它适合一拍二/一拍三，但运动、字幕和切镜必须逐帧 SR，不能保证最坏内容的算力。
2. dirty tile、差分卷积和 recurrent VSR 能利用视频稀疏变化。DeltaCNN 公开展示了对视频帧
   差分的稀疏 CNN 推理；FRVSR/RLSP 通过前帧状态减少重复计算。它们都改变执行语义甚至
   训练方式，适合作为后续模型/内核研究，不能作为当前 QuickSR 每帧等价保证。
   [DeltaCNN 论文](https://openaccess.thecvf.com/content/CVPR2022/html/Parger_DeltaCNN_End-to-End_CNN_Inference_of_Sparse_Frame_Differences_in_Videos_CVPR_2022_paper.html)、
   [DeltaCNN 源码](https://github.com/facebookresearch/DeltaCNN)、
   [FRVSR 论文](https://openaccess.thecvf.com/content_cvpr_2018/html/Sajjadi_Frame-Recurrent_Video_Super-Resolution_CVPR_2018_paper.html)、
   [RLSP](https://ar5iv.labs.arxiv.org/html/1909.08080)

## 5. 下一步顺序与停止条件

```text
物理 Qualcomm 设备重新出现
  -> A0：重跑已接单线程 alpha-map 的 OVERLAP 基线，确认收益/回归和当前可复现值
  -> B1：同权重 u8-NHWC-RGB 图是否能 QNN 全图编译/执行
       -> 先做 CPU/QNN 全帧逐字节 parity
       -> 再接 RGB8 texture，做固定片源 ABBA
       -> 两次 B 都 >= 23.976 fps 且队列不持续增长，才进入长时门
  -> 若 B1 的 graph op 回退 CPU或仍未达标
       -> B2：native ORT/QNN shared allocator，只改 I/O ownership
  -> 若 QNN caller 仍超过预算
       -> C1：同权重 ncnn/Vulkan GPU-resident 路线
       -> C2：仅在 profile 支持时测双 QNN graph
  -> 上述合理候选均失败：QuickSR 1080p 实时路线 STOPPED，转 Anime4K/更轻模型
```

真机 B1 之前不再花时间扩大队列、重做已失败的 JNI/NEON packer、把 batch=2 接进播放器，
也不先做 VFI。硬验收仍是 23.976 fps 每 PTS 输出、effect drop/bypass=0、队列无长期增长；
吞吐通过后再测 GPU completion、最终显示、A/V sync 和 10～30 分钟 thermal。

## 6. 可复现命令

```powershell
& .\build\fixed512-python-env\Scripts\python.exe .\scripts\experiment_display_friendly_output.py `
  --source .\derived-models\quicksrnet-small-3x-fixed640x360.onnx `
  --output .\build\experiments\quicksrnet-small-3x-fixed640x360-u8-nhwc.onnx

& .\build\fixed512-python-env\Scripts\python.exe .\scripts\experiment_batch_aggregation.py `
  --source .\derived-models\quicksrnet-small-3x-fixed640x360.onnx `
  --output .\build\experiments\quicksrnet-small-3x-fixed640x360-batch2.onnx --batch 2

& .\build\fixed512-python-env\Scripts\python.exe .\scripts\experiment_spatial_tile_batch.py `
  --source .\derived-models\quicksrnet-small-3x-fixed640x360.onnx `
  --output .\build\experiments\quicksrnet-small-3x-fixed640x360-spatial-batch2.onnx
```

Android 构建和模拟器测试需显式使用本机 SDK 路径；本轮没有修改全局环境变量。
