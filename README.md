# QuickSR Mobile Player Lab

Android 端图片与视频超分实验 App。当前版本为 **v0.14.0**：使用 Media3 播放本地视频，并通过 ONNX Runtime 在 CPU 或 Qualcomm QNN HTP/NPU 上逐帧运行 QuickSRNetSmall 2×/3×/4×。

> 当前状态（2026-09-01）
>
> - **App 已实现：**本地图片整图 2×、PNG 保存、本地视频播放、上一次视频一键重播、原画/GPU Lanczos/QuickSR CPU/QuickSR QNN HTP 切换。
> - **默认视频档：**`640×360 → 1280×720`、16:9、QNN HTP Sustained。
> - **既有物理机实测：**此前 v0.12.0 使用 `1280×720 @ 23.976023 fps` 片源；26.860 秒 smoke 中处理 645 帧，折算 `24.0134 fps`，四个约 5 秒 MediaCodec 窗口均 Render=120、Drop=0。
> - **高分辨率模拟器实测：**API 35 x86_64 CPU 路径已分别生成 `1920×1080`、`2560×1440` 神经纹理，并把 `1920×1080` 神经纹理放到 `3840×2160` GL 画布；这只证明功能和尺寸，不代表 QNN 实时性能。
> - **自动化已补齐：**视频 benchmark 可由 Intent 固定运行 ID、后端、分辨率档和 tuning，并向 Logcat 分批输出逐帧原始阶段耗时；设备脚本拒绝模拟器和非 arm64 设备。
> - **构建已通过：**52 个 Java 单测、6 个设备日志验证器单测、17 个 PC 矩阵单测、Android lint、x86_64/arm64-v8a debug assemble；arm64-v8a APK SHA-256 为 `297d87b0a88a1212f7e55b07c4c51cb789155a60ae3628e3dd92650e4e90dc61`。
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
  - `640×360 → 1920×1080`（3× 实验档）；
  - `640×360 → 2560×1440`（4× 实验档）；
  - `640×360 → 1920×1080 → 3840×2160 GL 画布`（4K 显示保底，不是原生 4K 神经推理）；
  - `512×512 → 1024×1024`；
- QNN 提供 Baseline、Burst 和 Sustained 三种实验 tuning；
- UI 分开显示排队、输入转换、ORT/QNN run、输出转换和整帧处理时间。

这些视频档会先把解码帧缩放到模型的静态输入尺寸，再把所选倍率的神经输出交回 GL 播放链路。它们不是对原始高分辨率帧逐块全覆盖的方案；4K 保底档也只是 1080p 神经纹理经 GPU 显示缩放。

## 已存档的 v0.12.0 物理机 720p smoke

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
| 主机构建 | 48 tests PASS；lint PASS；x86_64/arm64-v8a assemble PASS |

单次 UI 抽样是每 15 帧显示的一帧、毫秒向下取整，不是平均值或 p50/p95。`OrtSession.run` 是主机侧 wall time，包含 JNI、provider 与可能的数据移动，不应称为纯 NPU kernel 时间。

详细的优化过程、失败归因与下一步见 [实时视频超分经验总结](docs/REALTIME_VIDEO_SR_LESSONS.md)，正式状态边界见 [项目状态](docs/STATUS.md)。

## PC-first 动漫目标矩阵

`pc-benchmark/` 已把 16:9 360p/480p/720p 与方形低分辨率输入，到 1080p、1440p、2160p 的 18 条路线固化为机器可读计划。权利清晰语料包含一张 CC BY 3.0 开源动画帧，以及 CC BY 4.0 的原生 4K 横幅插画和方形漫画；方形素材始终等比放大后居中留边，不拉伸。本机已导出并验证 QuickSRNetSmall 1.5×/2×/3×/4× 动态 ONNX，2.25×/4.5×/6× 使用显式的混合缩放或级联策略。

完整语料运行覆盖 72 个“资产 × 宽高比路线 × 退化”案例：干净输入 QuickSR PSNR 胜 30/36，模糊加 JPEG Q35 仅胜 7/36；方形漫画素材总计胜 16/18。结果说明当前 Small 模型擅长干净线稿/漫画，但古早压缩内容需要退化自适应模型或回退 Lanczos。该语料仍不是代表性日本商业动漫数据集，也不会用 PC CPU 时间推断手机 QNN HTP 性能。运行方法见 [PC-first benchmark](pc-benchmark/README.md)，结果和下一步见 [模型与目标分辨率计划](docs/MODEL_VARIANT_PLAN.md)。

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

## 真机 QNN 分辨率矩阵

连接一台 arm64 Qualcomm 手机后，可把系统媒体库中已授权给 App 的视频 URI 传给一键脚本：

```powershell
.\scripts\run-android-qnn-resolution-matrix.ps1 `
  -ApkPath .\app\build\outputs\apk\debug\app-debug.apk `
  -VideoUri 'content://media/external/video/media/<id>'
```

脚本按 [机器可读测试计划](contracts/android-qnn-resolution-plan.json) 运行 720p、原生神经 1080p、原生神经 1440p 和“神经 1080p→GPU 4K”四档。每档丢弃 15 帧 warm-up，检查后端必须为 QNN HTP、tuning 必须为 Sustained、模型/画布尺寸必须吻合且不能有设备错误，再分别报告功能门禁和 `realtime_30`、`realtime_24` 或 `offline` 性能分类。原始日志和报告只写入被 Git 忽略的 `device-results/`。

模拟器只用于 CPU 遥测链路检查。脚本会拒绝 `ro.kernel.qemu=1`，App 自身也会在 x86 构建收到 `QUICKSR_QNN` benchmark 请求时输出结构化配置错误，而不是悄悄回退 CPU。

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
- QuickSR 在权利清晰 HR reference 上对所有倍率、退化和动漫内容都优于 Lanczos；当前只确认干净低分辨率子集有优势，压缩退化和部分 720p 路线会落后；
- pinned output 单项独立贡献了全部加速；
- 当前模型权重或含权重 APK 可以公开再分发；
- 已交付可供其他 App 直接依赖的独立 AAR。

历史 P3 HTP 执行与旧正确性失败边界见 [P3 证据摘要](docs/P3_EVIDENCE_SUMMARY.md)。
