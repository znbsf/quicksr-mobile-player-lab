# QuickSR Mobile Player Lab

Android 端图片与视频超分实验 App。当前版本为 **v0.12.0**：使用 Media3 播放本地视频，并通过 ONNX Runtime QNN Execution Provider 在 Qualcomm HTP/NPU 上逐帧运行 QuickSRNetSmall 2×。

> 当前状态（2026-09-01）
>
> - **App 已实现：**本地图片整图 2×、PNG 保存、本地视频播放、上一次视频一键重播、原画/GPU Lanczos/QuickSR CPU/QuickSR QNN HTP 切换。
> - **默认视频档：**`640×360 → 1280×720`、16:9、QNN HTP Sustained。
> - **指定设备与片源实测：**源视频为 `1280×720 @ 23.976023 fps`；最终 APK 的 26.860 秒 smoke 中处理 645 帧，折算 `24.0134 fps`，四个约 5 秒 MediaCodec 窗口均 Render=120、Drop=0。
> - **构建已通过：**44 个 Java 单测、Android lint、debug assemble；最终 APK SHA-256 为 `9d1a2153a844af29ddd883441c4e063217504b6e2cb649cce44aa0f64d0abf8e`。
> - **证据边界：**上述 FPS/Drop 是单设备、单 SDR 本地片源的 decoder-renderer 代理结果，不是所有手机/片源的通用 720p 实时承诺，也不是 SurfaceFlinger 最终 latch、A/V sync、画质正确性或长期热稳定性证明。

开发仓库：[znbsf/quicksr-mobile-player-lab](https://github.com/znbsf/quicksr-mobile-player-lab)（private、source-only；不提交模型、APK、私人媒体或原始真机证据）。

## App 能做什么

### 图片

- 从系统选图器读取本地图片；
- 在 CPU 或 QNN HTP 后端执行完整图片 2×；
- 按内存上限选择 960、1440 或 1920 像素级处理档；
- 显示源图和输出图，并把结果保存为 PNG；
- 对 tile、边缘、取消、生命周期和资源释放做显式处理。

### 视频

- 通过 Media3/ExoPlayer 播放本地非 DRM 视频；
- 保存系统授予的持久 URI 权限，并在下次启动显示“播放上一次视频”；
- 在四种模式之间切换：
  - QuickSR QNN HTP；
  - QuickSR CPU；
  - GPU Lanczos；
  - 原始画面；
- 支持以下静态神经输入/输出档：
  - `64×64 → 128×128`；
  - `256×144 → 512×288`；
  - `256×256 → 512×512`；
  - `512×288 → 1024×576`；
  - `640×360 → 1280×720`（默认）；
  - `512×512 → 1024×1024`；
- QNN 提供 Baseline、Burst 和 Sustained 三种实验 tuning；
- UI 分开显示排队、输入转换、ORT/QNN run、输出转换和整帧处理时间。

这些视频档会先把解码帧缩放到模型的静态输入尺寸，再把 2× 神经输出交回 GL 播放链路。它们不是把原始 720p/1080p 每个像素分块覆盖后再 2× 的完整超分方案。

## 最终构建 smoke

| 项目 | 结果 |
| --- | --- |
| App | v0.12.0 / versionCode 16 |
| 源视频 | `1280×720 @ 23.976023 fps` |
| 模式 | QNN HTP Sustained |
| 神经 shape | `640×360 → 1280×720` |
| 已处理帧 / PTS | `645 / 26.860 s` |
| 折算速率 | `24.0134 fps` |
| 稳定 MediaCodec 窗口 | 4 个窗口均约 `120 / 5 s`，Drop=0 |
| 单次 UI 抽样 | ORT/QNN run 9 ms；输出转换 10 ms；整段 22 ms |
| 主机构建 | 44 tests PASS；lint PASS；assemble PASS |

单次 UI 抽样是每 15 帧显示的一帧、毫秒向下取整，不是平均值或 p50/p95。`OrtSession.run` 是主机侧 wall time，包含 JNI、provider 与可能的数据移动，不应称为纯 NPU kernel 时间。

详细的优化过程、失败归因与下一步见 [实时视频超分经验总结](docs/REALTIME_VIDEO_SR_LESSONS.md)，正式状态边界见 [项目状态](docs/STATUS.md)。

## PC-first 动漫目标矩阵

`pc-benchmark/` 已把 16:9 360p/480p/720p 与方形低分辨率输入，到 1080p、1440p、2160p 的 18 条路线固化为机器可读计划。方形素材始终等比放大后居中留边，不拉伸；1.5×/2×/3×/4× 以直接模型为目标，2.25×/4.5×/6× 使用显式的混合缩放或级联策略。

该目录同时提供确定性的合成动漫风 HR/LR 对和本机 ONNX Runtime CPU 基线，用于先验证退化、推理、指标与报告链路。它不会把合成素材分数冒充真实动漫数据集结论，也不会用 PC CPU 时间推断手机 QNN HTP 性能。运行方法见 [PC-first benchmark](pc-benchmark/README.md)。

## 为什么没有重写播放器为 C

已观测到解码器走 Qualcomm 硬件 MediaCodec，GPU Lanczos 与最终 QNN 档都能跟住 23.976 fps 片源。早期慢点主要出现在每帧 tensor/output 生命周期、CPU↔GL↔ORT/QNN 边界和排队，而不是播放器控制层本身。

因此当前保留 Media3 的解码、时间轴、seek、Surface 与 UI，只优化 effect 热路径。改用 C/C++ 只有在 profiler 证明 Java/JNI、RGBA 转换或 GL upload 是主要瓶颈时才有收益；“全部换 C”本身不是通用解法。

## 本地模型准备

模型和派生 ONNX 都是本地输入，不属于 GitHub 源码内容。canonical 模型的冻结身份为：

```text
bytes:   93994
sha256:  3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce
```

把经过独立来源核验的 canonical ONNX 放入 `models/`，再按 [模型说明](models/README.md) 运行 fixed-shape 派生脚本。全新 source-only checkout 不包含这些模型，因此不能直接 assemble；这是预期的 fail-closed 行为。

## 构建

项目需要 Android SDK、Java 17+、Gradle 8.14，以及文档所列的本地模型。仓库根目录运行：

```powershell
.\build-local.ps1
```

也可以覆盖 canonical 模型位置：

```powershell
.\build-local.ps1 -ModelPath <local-canonical-onnx>
```

脚本会验证模型、派生 manifest 与文件 hash，并依次运行 unit tests、lint、assemble 和 source/build identity linkage。构建成功不自动等于真机性能、画质或发布许可通过。

## Source-only GitHub 边界

允许进入 Git 的内容包括原创 Java/Python/PowerShell 源码、Gradle 配置、测试、派生 manifest、hash、架构和脱敏汇总。以下内容必须留在本地：

- checkpoint、ONNX 和其他模型权重；
- APK/AAB/AAR、Qualcomm/ORT `.so` 与其他编译二进制；
- 原始 tensor、receipt、profile、QNN CSV/trace 和 Android 日志；
- 私人图片、视频、截图、EXIF、URI、设备序列号或本机绝对路径；
- 凭据、签名材料和未确认可再分发的第三方内容。

提交前运行：

```powershell
.\scripts\verify-publication.ps1
```

自动检查 PASS 只说明候选文件没有命中已知泄漏规则，不授予模型、数据集、媒体、vendor binary 或 APK 的再分发许可。完整规则见 [发布边界](docs/PUBLICATION_BOUNDARY.md) 和 [第三方说明](THIRD_PARTY_NOTICES.md)。

## 当前仍不能声称

- 所有 Qualcomm 手机或所有 720p 视频都能实时；
- MediaCodec Render/Drop 等于最终屏幕实际显示帧率；
- 已有完整的 p50/p95/p99、A/V sync、功耗与降频报告；
- QuickSR 画质已经在权利清晰的 HR reference 上优于 Lanczos；
- pinned output 单项独立贡献了全部加速；
- 当前模型权重或含权重 APK 可以公开再分发；
- 已交付可供其他 App 直接依赖的独立 AAR。

历史 P3 HTP 执行与旧正确性失败边界见 [P3 证据摘要](docs/P3_EVIDENCE_SUMMARY.md)。
