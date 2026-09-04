# 从一帧画面到实时播放

端侧模型部署学习报告 · QuickSR / Android / QNN

[中文首页](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/README.zh-CN.md) · [English overview](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/README.md) · [项目状态](STATUS.md)

这个项目要解决一个具体问题：把视频画面送进手机上的超分模型，得到更高分辨率的画面，同时跟上原片帧率。围绕这件事，我们需要学会准备模型、组织数据、调用加速器，以及把结果准时交还给播放器。

本文以 **`640×360 → 1920×1080，30fps`** 为贯穿案例。阅读前只需要理解数组、函数调用和线程的基本概念；遇到 ONNX、Tensor、QNN 等名词时，正文会在第一次使用处解释。

截至 **2026-09-05** 的已记录实验，项目在一台 Android 16 / SM8550 设备上达到约 30fps 平均处理吞吐，最终显示仍偶发长帧。接下来既要理解这些优化怎样做到，也要理解为什么它们还没有完成“保证原片帧率”的目标。本文数据引用已有实验，最新进展以[状态页](STATUS.md)为准。

## 阅读路线

1. [明确目标：输入、输出与帧率](#case)——先知道我们在部署什么。
2. [跟着一帧走：纹理、Tensor 与内存](#frame)——看懂数据为什么要来回搬运。
3. [把模型接进 App：文件、运行时与代码](#deployment)——把一次推理接到真实播放器。
4. [让它跟上视频：四个优化案例](#optimization)——理解复用、布局、流水线和工作量削减。
5. [解释实验结果：为什么 30fps 还会卡](#results)——区分吞吐、延迟和显示节奏。
6. [动手学习：从无模型练习到真机验证](#practice)——每一步都有明确产出。

第一次顺读正文即可；具体模型矩阵、历史试验和图的维护方法收在[附录](#reference)，需要复现时再查。

<a id="case"></a>

## 1. 明确目标：输入、输出与帧率

### 1.1 这里的“1080p 超分”指什么

超分辨率（Super-Resolution，SR）用低分辨率图像估计更高分辨率图像。这个案例调用 QuickSRNetSmall 3×：宽、高各扩大 3 倍，所以输出像素数是输入的 **9 倍**。

| 项目 | 本文使用的具体值 |
| --- | --- |
| 进入模型的画面 | `640×360`，RGB 三个颜色通道 |
| 模型产生的画面 | `1920×1080`，RGB 三个颜色通道 |
| 最终交给播放器 | `1920×1080` RGBA8 纹理，携带原帧的呈现时间 |
| 实时目标 | 对冻结的 30fps 片源，每个源帧都得到正确、及时的输出 |

`640×360` 是这个档位的**神经输入尺寸**。源文件可以更大，由前处理缩放到该尺寸；因此这里的“1080p”指神经输出，不能读成“拿原生 1080p 做神经 4K”。当前 4K 显示档另有一次 `1080p → 4K` 的 GPU 缩放。

这个模型每次只看一帧，没有前后帧输入。把它接到视频播放循环中，也不会自动变成利用时间信息的视频超分模型。

### 1.2 帧率是时间要求

30fps 意味着每隔约 `1000 / 30 = 33.33ms` 就有一张新画面需要呈现。播放器用 PTS（Presentation Timestamp，呈现时间戳）表示画面在视频时间轴上的位置；模型输出要继续对应原来的 PTS。

可以先用两个问题检查一个实现：它能持续产出每秒 30 张增强画面吗？这些画面是否按正确时间间隔显示？前一个问题对应**吞吐**，后一个对应**显示节奏**。后文的实验会说明两者为什么可能得出不同答案。

在当前项目顺序中，先完成逐帧播放的帧率要求，再评估代表性动漫画质、其他模型和插帧。插帧会生成新的时间点，是另一类任务。

<a id="frame"></a>

## 2. 跟着一帧走：纹理、Tensor 与内存

### 2.1 同一幅画面有不同的数据表示

播放器和模型对数据的要求不同。Media3 的视频效果接口处理 OpenGL 纹理；模型接收的是 Tensor，也就是带有形状和数据类型的多维数组。

当前模型输入是 float32（每个数 4 字节），将 RGB 字节值除以 255，归一化到 `[0,1]`。纹理和显示前缓冲采用 RGBA8：每个像素的红、绿、蓝、透明度各占 1 字节。模型只预测 RGB，alpha 从输入旁路保存，在后处理时按位置合并。

NCHW、NHWC 描述同一组数字怎样排列。N 是批大小，C 是通道，H、W 是高和宽。以两个像素为例：

```text
NCHW  R0 R1 | G0 G1 | B0 B1       同一通道放在一起
NHWC  R0 G0 B0 | R1 G1 B1         同一像素放在一起
RGBA8 R0 G0 B0 A0 | R1 G1 B1 A1   每通道变成一个字节
```

换布局会改变索引方式，不会减少元素数量。输出打包时，把 RGB float 限制到 `[0,1]`、乘以 255 并舍入，转换成字节，再加上 alpha；这一步才涉及数值范围和数据类型变化。

### 2.2 数据实际经过哪些边界

![一帧从输入纹理，经 CPU 输入张量、QNN、CPU 输出张量，回到显示纹理](diagrams/edge-frame-dataflow.svg)

*图 1：只看数据表示。框中的输入、输出分别属于 CPU 可访问缓冲或 GL 纹理；箭头概括转换步骤。[PlantUML 源码](diagrams/edge-frame-dataflow.puml)*

沿图读一遍实际实现：

1. Media3 准备 `640×360` 的 RGBA 画面，读回 CPU 可访问缓冲。
2. CPU 把 RGBA 拆成归一化的 RGB float32 NCHW 数组，再复制进会话复用的 direct input buffer。
3. ONNX Runtime 调用 QNN HTP，结果写入预先创建的 float32 NHWC output tensor。
4. 后处理线程把 output tensor 批量复制到应用数组，用**四条带 Java 打包**转换为 RGBA8，再写入可上传的 direct buffer。
5. GL 线程上传到 Media3 的输出纹理，沿用原 PTS 进入显示流程。

这就是 `GL → CPU → QNN → CPU → GL`。这里的 QNN 是软件运行时边界；图没有展开驱动内部的传输，也不代表 CPU 与 HTP 已共享物理内存。当前路径仍包含复制和同步。

### 2.3 手算一次内存，就能看懂很多优化

```text
Tensor 字节数 = 各维度相乘 × 每个元素的字节数
MiB = 字节数 / 1,048,576

1080p RGB float32 输出：
1 × 1080 × 1920 × 3 × 4 = 24,883,200 B ≈ 23.73 MiB
```

| 一个缓冲的用途 | 格式或形状 | 大小 |
| --- | --- | ---: |
| 输入读回 | RGBA8，`640×360` | 0.879 MiB |
| 模型输入 | float32，`[1,3,360,640]` | 2.637 MiB |
| 模型输出 | float32，`[1,1080,1920,3]` | 23.730 MiB |
| 显示前输出 | RGBA8，`1920×1080` | 7.910 MiB |

这解释了为什么约 0.1 MiB 的模型文件仍会产生明显内存压力：权重是计算规则，输出是每帧生成的数百万个值。当前两个预分配 output tensor 合计 **47.461 MiB**，另有应用侧数组、纹理、模型中间结果和播放器缓冲。

这些是单个显式缓冲的大小，不能相加就当作进程总内存。理解量级之后，再用真机内存记录检查实际峰值。

读到这里应能回答：NHWC 为什么可能更容易转成 RGBA？为什么 NHWC 输出仍然占 23.73 MiB？

<a id="deployment"></a>

## 3. 把模型接进 App：文件、运行时与代码

### 3.1 模型文件进入手机前，要先确定接口

ONNX 保存模型计算图和权重。调用它之前，应用需要知道输入输出名字、形状、类型和排列方式；这些信息与文件身份一起组成部署约定。

本文主档的具体约定如下，取自 [`ModelVariant.java`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/main/java/dev/aisystems/quicksrplayerlab/ModelVariant.java) 与[构建配置](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/build.gradle.kts)：

| 字段 | 1080p 默认变体 |
| --- | --- |
| 模型 | `quicksrnet-small-3x-fixed640x360-f32-nhwc.onnx` |
| 输入 | `image`，float32 NCHW `[1,3,360,640]` |
| 输出 | `upscaled_image__display_f32_nhwc`，float32 NHWC `[1,1080,1920,3]` |
| 文件身份 | 111,420 B；SHA-256 前缀 `9a9fd7cd…`，完整值由构建配置校验 |

部署过程可以按四步理解：取得已核验来源的模型；导出或派生所需形状与布局；在电脑上比较变换前后的数值；通过构建检查后打包到 App。文件名可以相同而内容不同，因此构建和加载时都校验长度与哈希。

有两种变换需要分清：

- **固定 shape**：把动态高宽变成确定尺寸，让缓冲复用和后端准备更可预测。代价是每个档位需要单独准备和验证。
- **NCHW → NHWC 输出包装**：保持该倍率的卷积权重不变，改变公开输出的排列。它仍然需要数值比较。

3× 模型来自对应倍率的权重导出，不能把 2× 输出尺寸改一改就得到。正确性比较也应比较“同一个模型变换前后”；它回答的是变换是否正确，画质评估还需要高分辨率参考画面。

### 3.2 ONNX Runtime、QNN 和 HTP 各做什么

ONNX Runtime（ORT）负责加载图、管理会话并执行推理；Execution Provider（EP，执行后端）负责把图交给具体设备。本项目选 QNN EP，调用 Qualcomm 的运行时和 HTP 加速后端。

因此，`OrtSession.run()` 的耗时包含运行时调用链，不能直接当作纯 NPU 运算时间。当前真机记录确认了 HTP 配置并禁用了 CPU EP 静默回退；逐节点究竟分配给哪个后端，还需要相应的执行跟踪。

项目使用的版本固定在构建配置中：Media3 `1.11.0`、ORT Android `1.26.0`、QNN plugin `2.5.0`、QNN Runtime `2.49.0`。`SUSTAINED` 是持续性能配置请求；长期频率、温度和功耗仍要另测。

### 3.3 代码按什么职责分工

![播放器接入调用帧处理编排，编排使用推理会话和遥测，会话准备 QNN 后端](diagrams/edge-code-architecture.svg)

*图 2：只看代码依赖。箭头表示调用或使用；数据的返回和线程时序不在这张图展开。[PlantUML 源码](diagrams/edge-code-architecture.puml)*

建议按下面的顺序读，每次带着一个问题：

| 读哪个文件 | 找到什么答案 |
| --- | --- |
| [`ModelVariant`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/main/java/dev/aisystems/quicksrplayerlab/ModelVariant.java) | 输入输出有多大、怎样解释？ |
| [`QuickSrSession`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/main/java/dev/aisystems/quicksrplayerlab/QuickSrSession.java) | 会话和缓冲什么时候创建、复用、释放？ |
| [`QnnPluginRuntime`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/main/java/dev/aisystems/quicksrplayerlab/QnnPluginRuntime.java) | 后端怎么注册，配置失败如何暴露？ |
| [`QuickSrVideoEffect`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/main/java/dev/aisystems/quicksrplayerlab/QuickSrVideoEffect.java) | `processImage` 后如何推理、后处理、交还输出？ |
| [`VideoPipelineTelemetry`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/main/java/dev/aisystems/quicksrplayerlab/VideoPipelineTelemetry.java) | 怎样把同一帧各阶段的时间对齐？ |
| [`SuperResolutionActivity`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/main/java/dev/aisystems/quicksrplayerlab/SuperResolutionActivity.java) | 用户选择怎样连接播放器和 effect？ |

先读会话，再读几千行的 effect，会更容易区分模型调用、资源管理和播放器适配。

### 3.4 一次调用的最小心智模型

下面是当前 App 内部 API 的教学片段。`context`、`runId` 和已完成预处理的 `inputNchw` 由调用方提供；片段省略了纹理和播放器生命周期，不是独立可运行的 Android 示例。

```java
ModelVariant variant = ModelVariant.FIXED640X360_3X_F32_NHWC;
float[] outputNhwc = new float[variant.outputValueCount()];

try (QuickSrSession session = QuickSrSession.open(
        context, QuickSrSession.Mode.QNN_HTP, runId,
        variant, QuickSrSession.Tuning.SUSTAINED, 2)) {
    session.infer(inputNchw, outputNhwc);
    // outputNhwc 是 RGB float；仍需打包和上传才能显示。
}
```

连续播放时，会话和缓冲放在**帧循环外**创建，循环内更新输入并执行推理。流结束后先让使用资源的任务退出，再释放会话；如果每一帧都照着上面的片段重新 `open/close`，就会反复支付初始化成本。

读到这里应能回答：模型返回了数组，为什么播放工作还没完成？谁负责把它变回画面？

<a id="optimization"></a>

## 4. 让它跟上视频：四个优化案例

项目早期先验证 QNN 能执行，再建立 720p 路径；提高到 1080p 后，输出搬运、后处理和串行等待变得突出。下面按解决问题的层次讲这段过程，历史数值来自各自实验，不能把各项收益相加。

### 案例一：把每帧重复准备的资源留在会话中

**问题。** 持续视频不断用相同形状调用相同模型，逐帧创建会话、Tensor 和大数组会增加初始化、分配和回收工作。

**实现。** 固定 16:9 shape，持久化 ORT session、direct input buffer 和 output tensor，复用应用缓冲。这里的 pinned output 指应用预先提供、让 ORT 写入的输出 Tensor；它本身不证明与 GPU 或 HTP 零拷贝共享。

**结果与代价。** 早期 720p 已在指定 23.976fps 片源上跟住播放器代理帧率。那一轮还同时调整了 QNN tuning 等设置，没有把 pinned output 的独立收益分离出来。复用减少重复准备，但所有权和关闭顺序变得更重要。

对应代码：`QuickSrSession.open()`、`runIntoSlot()`、`close()`；历史记录：[720p 优化经验](REALTIME_VIDEO_SR_LESSONS.md)。

### 案例二：按输出消费者需要的顺序访问数据

**问题。** 1080p NCHW 输出有三个大颜色平面。打包一个像素时，要从三个区域分别取值，再写出交错 RGBA。

**实现。** 输出改成 float32 NHWC；把输出图像按行分成四条带，每个 Java worker 写入不同的输出区域；alpha 对应的输入索引提前计算。布局优化改善访问方式，条带并行利用 CPU 处理不同的行。

**结果与代价。** 最终配置的 pack p50/p95 为 `11.321/15.503ms`，两条带的实测尾延迟更差。这里采用的是**四条带 Java 实现**；先前 JNI/NEON 候选没有进入默认路径。输出仍然是 23.73 MiB 的 float 数据，线程也会争用内存带宽。

对应代码：`packNhwcToRgbaWithAlphaMapsParallel()`；最终记录：[架构优化审计](REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md)。

### 案例三：让不同帧处在不同处理阶段

**问题。** 串行执行时，QNN 算完 N 帧，还要等 N 帧复制、打包和交付结束，才能继续下一帧。

**实现。** 推理、CPU 后处理和 GL 交付分阶段调度。下面只表示同一个时间窗口内可以发生的跨帧重叠；横轴是示意时隙，**没有使用实测耗时**。

![流水线三个阶段在相同时间窗口内分别处理相邻帧，随后各自推进到下一帧](diagrams/edge-pipeline-overlap.svg)

*图 3：只看时间重叠。后处理包括 output copy 和四条带 Java pack；GL 行表示上传和提交，最终屏幕呈现还有后续调度。[PlantUML 源码](diagrams/edge-pipeline-overlap.puml)*

用一组纯教学数字算一遍：如果推理 30ms、后处理 12ms、GL 交付 5ms，串行服务间隔约为 `30 + 12 + 5 = 47ms`；充分重叠后的理想稳态间隔接近最慢阶段的 30ms。单帧依然要经过所有阶段，真实系统还会增加等待和竞争。

在这个实现中，两个 pinned output slot 允许上一帧复制时，下一帧写入另一个 slot。`inferDeferred()` 返回临时租约（lease）；后处理用 `copyTo()` 取走数据，再 `close()` 归还槽位。它只把输出复制移到后处理线程，QNN 推理仍由同一条推理执行线串行调用。

**结果与代价。** 单独的 deferred-copy A/B/B/A 中，推理调用 p95 的两轮均值由 `34.733 → 30.529ms`，接收至提交 p95 的两轮均值由 `195.203 → 178.195ms`。A、B 均已达到源速率，因此这组结果主要说明关键路径和延迟改善。额外 output slot 占 **23.73 MiB**。[实验摘要](evidence/realtime-1080p-physical-20260905.json)

流水线还需要三条约束：

- **有界等待。** 当前应用帧准入上限为 2，下游来不及就施加背压。这个 2 不等于系统所有缓冲总共只能放两帧；Media3、后处理、GL 各自还有生命周期。
- **帧身份。** `frameId` 串起计时，PTS 保留播放位置，`generation` 在 seek/flush 时更新，旧任务完成后不能混入新时间轴。
- **明确所有权。** 消费者复制完之前不能复用输出槽；复制后及时归还。释放会话前要确认仍在使用它的任务已退出。

加深队列只能扩大等待空间。如果模型持续只能处理 20fps，而输入是 30fps，每秒仍会多积压 10 帧，最终必须阻塞或丢帧。

### 案例四：移走不该出现在稳定播放中的工作

**问题。** 即使平均计算够快，偶发大工作也会打断帧节奏。旧完整有限值扫描会周期遍历超过 600 万个 float，真机曾出现约 `330～353ms` 的停顿。

**实现。** 当前会话第一帧完整检查 NaN/Infinity，后续帧保留尺寸和生命周期检查，但不再周期性全量扫描。同时，同尺寸输出直接上传 Media3 texture，省掉私有中间纹理和一次缩放 blit。

**结果与代价。** 移除了已观测的周期全扫停顿；direct upload 的一次对照中，GL 提交 p95 从 `6.435 → 3.460ms`。首帧检查不代表后续每帧都做了同等数值验证；4K 画布尺寸不同时仍需缩放。局部工作减少后，还要重新检查最终显示。[审计与边界](REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md)

### 为什么有些“更底层”的优化没有采用

| 尝试 | 实验告诉我们什么 | 从中学到什么 |
| --- | --- | --- |
| JNI/NEON packer | 旧串行 NCHW A/B/B/A 中，pack p50 两轮均值 `36.566 → 102.353ms`，平均吞吐下降 42.94% | 这一个实现回归；不能由此断言 NEON 本身更慢，JNI、访存等成本尚未分离 |
| 双 PBO 上传 | 对照中 GL p95 `3.460 → 1.832ms`，最终显示仍只有 1/2 通过，额外占 15.82 MiB | CPU 提交变快不必然改善显示节奏 |
| 固定“每三帧超分一次” | 当前没有作为实时默认策略；cadence analyzer 与受控复用另有实验 | 动漫会混合静止背景、运动、字幕和切镜，一拍三不能替代逐帧内容判断 |

更多候选（直接 FloatBuffer 读取、时域 batch、空间分块）保留在[历史试验索引](#experiments)。读负结果时，首先确认它测试了哪个实现和哪个配置。

<a id="results"></a>

## 5. 解释实验结果：为什么 30fps 还会卡

### 5.1 吞吐、延迟、显示间隔要分开读

| 指标 | 它回答的问题 | 已记录的 1080p 结果 |
| --- | --- | --- |
| 处理吞吐 | 稳态每秒向 Media3 交付多少帧？ | 828 个稳态样本，`30.0045fps`，effect drop/bypass `0/0` |
| 管线延迟 | 一帧从 effect 接收到输出提交，经过多久？ | p95 `179.024ms`，含处理和等待 |
| 显示间隔 | 相邻画面实际呈现代理的时间差是否稳定？ | QNN 两轮只有 1 轮通过；失败轮最大间隔 `58.153ms` |

流水线填满后，可以每约 33.33ms 交付一帧，同时每一帧在多个阶段和队列中停留更久。所以 `179ms` 管线延迟与 `30fps` 吞吐可以同时成立。

p50 是中位数，p95 是 95% 样本不超过的值。不同阶段的 p95 可能来自不同帧，不能直接相加或取最大值来推算真实 FPS；应看逐帧时间线和实测服务率。

### 5.2 看一次真正的显示对照

A/B/B/A 表示“原画、QuickSR、QuickSR、原画”，用于观察时间和温度漂移。SurfaceFlinger 是 Android 合成显示链路的一部分，下面测量的是其 layer actual-present 代理，尚非屏幕光子时序或音画同步。

| 轮次 | 路径 | 平均呈现 FPS | 长 / 短间隔异常 | 结果 |
| --- | --- | ---: | ---: | --- |
| A1 | 原画 | 29.9792 | 0 / 0 | 通过 |
| B1 | QuickSR QNN | 30.0341 | 1 / 2 | 未通过 |
| B2 | QuickSR QNN | 30.0030 | 0 / 0 | 通过 |
| A2 | 原画 | 29.9544 | 0 / 0 | 通过 |

这次规则把超过 `1.5 × 33.33ms` 的间隔记为长间隔，小于 `0.5 × 33.33ms` 的记为短间隔。它允许一定抖动；“通过”也不等于每帧都恰好 33.33ms。

B1 的均值看起来很好，但一张画面停留了 58.153ms，随后又有补偿短间隔。均值会把这种局部不均匀隐藏掉。由此能确认的是“已达到平均服务率，显示重复性仍未稳定通过”，尚不能确定是哪一行代码导致。

来源：[去标识的机器可读实验摘要](evidence/realtime-1080p-physical-20260905.json)。该片源没有音轨，当前默认架构的长期温度/功耗和代表性动漫画质也还未完成正式验证。

### 5.3 当前最值得做的下一步

把一次失败帧的 `frameId / PTS` 与应用计时、Perfetto 调度/频率、GL fence（GPU 工作完成的同步信号）、BufferQueue、SurfaceFlinger 时间线对齐，判断延误先出现在哪里：

| 如果首先发现 | 对应研究方向 |
| --- | --- |
| 推理或后处理结果晚到 | 执行耗时、CPU 调度、带宽争用 |
| 结果已好，但上传或 GPU 完成较晚 | GL 提交与同步，评估共享缓冲是否可行 |
| 提交及时，但错过显示采纳时机 | 播放器队列与 SurfaceFlinger 呈现调度 |
| 与抢占、频率变化同时出现 | 系统调度、温度与持续性能 |

几种原因可能同时作用；先找有证据的最早延误，保留无法判断的部分，再做对应的单变量修改。采用 AHardwareBuffer 或 EGLImage 之前，也要验证模型缓冲、纹理、格式和同步能否衔接，不能把 API 名称等同于整条链零拷贝。

详细执行顺序见[实现计划](IMPLEMENTATION_PLAN_AND_PROGRESS.md)。

<a id="practice"></a>

## 6. 动手学习：从无模型练习到真机验证

每一步先写下预测，再执行，再用结果解释预测为什么成立或失效。前两步就可以开始学习数据与工程思维。

### 练习一：不用模型，算清数据

运行下列纯 Python 示例，确认两份数组只是排列不同。然后把 H/W 换成本文输入和输出尺寸，算出 Tensor 字节数。

```python
# 两个 RGB 像素：(10, 20, 30)、(40, 50, 60)
nhwc = [10, 20, 30, 40, 50, 60]
nchw = [10, 40, 20, 50, 30, 60]
rebuilt = [nchw[c * 2 + p] for p in range(2) for c in range(3)]
assert rebuilt == nhwc
print(1 * 1080 * 1920 * 3 * 4)  # 24883200 bytes
```

产出：写出 NCHW 与 NHWC 的索引公式，解释“布局变了但字节数没有变”。可以对照 [`QuickSrVideoEffectTest`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/test/java/dev/aisystems/quicksrplayerlab/QuickSrVideoEffectTest.java) 中的转换用例。

### 练习二：不用手机，读出一个实验结论

在仓库根目录执行以下 PowerShell，只读取已提交的摘要：

```powershell
$report = Get-Content .\docs\evidence\realtime-1080p-physical-20260905.json -Raw |
    ConvertFrom-Json
$report.throughput | Select-Object observed_fps, measured_frames, dropped_count
$report.surfaceflinger_abba.quicksr_qnn |
    Select-Object run, status, actual_present_fps, maximum_interval_ms
```

产出：用两句话分别描述平均吞吐和显示节奏，指出这些数据为什么不能证明音画同步。此时不需要模型、Android SDK 或设备。

### 练习三：有本地模型后，验证一个图变换

本仓库提交代码和验证记录，权重及带权重 APK 留在本地。先按[模型准备说明](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/models/README.md)取得并核验所需文件；Python 环境需要 `numpy`、`onnx`、`onnxruntime`，具体准备见 [PC benchmark](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/pc-benchmark/README.md)。

可以先做 fixed-shape 2× 小练习；在 canonical 2× 与既有 fixed64 DCR 产物已按说明准备后，运行：

```powershell
python .\derived-models\derive_quicksrnet_fixed640x360.py
```

产出：检查它生成的 `derivation-manifest-fixed640x360.json`，找到来源哈希、输出 `[1,3,720,1280]` 和两组确定性输入的数值比较。这个练习是 720p 的 2× 图，和正文 1080p 主档要分清。

进一步做 3× NHWC 包装时，先按 [PC 导出说明](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/pc-benchmark/README.md)准备对应的 3× 权重和导出环境；再运行：

```powershell
python .\pc-benchmark\export_quicksrnet_variants.py --scale 3
python .\scripts\experiment_display_friendly_output.py `
    --source .\derived-models\quicksrnet-small-3x-fixed640x360.onnx `
    --output .\derived-models\quicksrnet-small-3x-fixed640x360-f32-nhwc.onnx `
    --variant float32-nhwc
```

导出还需要 `pc-benchmark/requirements-export.txt` 中的依赖；脚本执行 CPU 数值比较。产出：确认 NHWC 输出等于 NCHW 输出的转置，记录 shape、dtype 和比较结果。电脑上的计时只描述这个 CPU 环境。

### 练习四：在 Android 中追踪资源和帧身份

按[构建说明](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/README.zh-CN.md#构建与运行)准备 SDK、JDK、模型集合与依赖。完整 App 需要多个变体，仅完成练习三的单个模型不足以组装 APK。

阅读 `QuickSrSession.open/infer/close` 和 `QuickSrVideoEffect.processImage`，给一帧标出“输入缓冲、输出槽、应用数组、GL 纹理”分别由谁持有。再阅读 [`VideoPipelineTelemetryTest`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/src/test/java/dev/aisystems/quicksrplayerlab/VideoPipelineTelemetryTest.java)，理解 seek 后旧 generation 为什么必须被拒绝。

产出：解释租约过早归还和永不归还各会造成什么问题。有隔离模拟器时，可以检查 CPU 功能路径和 seek/release 行为；模拟器结果不用于推断真机 QNN 速度。

### 练习五：用真机回答一个性能问题

模型、片源、APK、QNN 设置和统计窗口保持一致，只比较一个因素，例如 deferred copy 开关。使用指定设备，记录每帧身份、各阶段计时、队列深度及显示间隔；同时保存后端配置。

产出：一份 A/B/B/A 记录，说明改动影响的是吞吐、管线延迟还是显示节奏。测试方法见[遥测说明](REALTIME_PIPELINE_TELEMETRY.md)、[最终实验摘要](evidence/realtime-1080p-physical-20260905.json)和[下一步计划](IMPLEMENTATION_PLAN_AND_PROGRESS.md)。后续再用有音轨、长时间、代表性动漫素材补齐其他问题。

<a id="reference"></a>

## 附录：需要时再查

<details>
<summary>模型档位、构建文件与来源</summary>

| 视频档位 | 神经输入 → 输出 | 用途 |
| --- | --- | --- |
| 720p，QuickSR 2× | `640×360 → 1280×720` | 诊断与回归 |
| 1080p，QuickSR 3× | `640×360 → 1920×1080` | 当前逐帧实时主目标；默认输出 NHWC |
| 1440p，QuickSR 4× | `640×360 → 2560×1440` | 高分辨率实验 |
| 4K 显示 | 3× 神经输出后，GPU 放大到 `3840×2160` | 非原生神经 4K |

图片路径、动态模型和视频静态模型的接口与测试条件不同，不能混用各自的性能结论。

完整 bytes/hash 以 [`app/build.gradle.kts`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/app/build.gradle.kts) 为准，来源和准备步骤见 [`models/README.md`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/models/README.md)、[`derived-models/README.md`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/derived-models/README.md)、[`pc-benchmark/model-sources.json`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/pc-benchmark/model-sources.json)。

构建入口 [`build-local.ps1`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/build-local.ps1) 校验本地资源后构建；加载时 `QuickSrSession` 再校验 asset 身份。新 checkout 不含权重，完整准备后才可 assemble。上游版本与项目发布约定分别见 [`THIRD_PARTY_NOTICES.md`](https://github.com/znbsf/quicksr-mobile-player-lab/blob/main/THIRD_PARTY_NOTICES.md) 和[发布边界](PUBLICATION_BOUNDARY.md)。

</details>

<a id="experiments"></a>

<details>
<summary>优化历史与动漫相关扩展</summary>

| 想继续理解的问题 | 阅读入口 |
| --- | --- |
| 从最初验证到各阶段实现 | [开发记录](DEVELOPMENT_LOG.md) |
| 720p 静态 shape、资源复用与调优 | [早期优化经验](REALTIME_VIDEO_SR_LESSONS.md) |
| 早期串行与 overlap 的真机比较 | [后处理重叠实验](ANDROID_QNN_POSTPROCESS_OVERLAP_AB.md) |
| 为什么 native packer 回归 | [JNI/NEON A/B/B/A](ANDROID_QNN_NATIVE_OUTPUT_PACKER_ABBA.md) |
| NHWC、deferred copy、PBO 和停止的候选 | [1080p 架构审计](REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md) |
| 按内容判断一拍二、一拍三能否复用 | [动漫 cadence 复用](ANIME_CADENCE_REUSE.md) |
| Anime4K 怎样留在 GPU 上处理纹理 | [Anime4K GPU 接入](ANIME4K_ANDROID_GPU_INTEGRATION.md) |
| RIFE、IFRNet 等插帧候选的移动探针 | [VFI 候选报告](ANIME_VFI_MOBILE_CANDIDATE_PROBE.md) |

历史文档保留当时配置；例如早期每 120 帧有限值全扫，后来改为首帧全扫，应按日期和[当前状态](STATUS.md)理解。Anime4K 是独立 GPU 路径，VFI 仍是离线/CLI 探针；它们的结果不能替代本文 QuickSR 逐帧显示结果。

</details>

<details>
<summary>三个图的职责与修改方法</summary>

- `edge-frame-dataflow`：一帧的数据表示与转换边界。
- `edge-code-architecture`：实现模块的调用/依赖。
- `edge-pipeline-overlap`：不同帧在阶段间重叠的示意。

每张图同时保留 PlantUML `.puml` 和可直接显示的 `.svg`。图中不放性能结论、完整哈希或实验分支，避免与正文重复。

使用本地 PlantUML 1.2026.6 重新生成；渲染器不进入仓库：

```powershell
$plantUmlJar = '<path-to-plantuml-1.2026.6.jar>'
Get-ChildItem .\docs\diagrams\edge-*.puml | ForEach-Object {
    java -jar $plantUmlJar -charset UTF-8 -tsvg $_.FullName
}
```

</details>
