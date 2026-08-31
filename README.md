# QuickSR Mobile Player Lab

> 当前状态（2026-08-31）
>
> - **已经证明：**此前 P3 在 Xiaomi 13 Ultra 上完成了 QuickSRNetSmall ×2 的 QNN HTP strict 执行资格验证；NPU 被枚举，CPU EP fallback 被禁用，ORT placement 与 QNN RPC/accelerator/HVX trace 均有证据。
> - **仍然失败：**同一次 P3 输出没有通过运行前冻结的 PC golden 正确性合同。原合同为 `atol=rtol=1e-4`、允许 mismatch 为 0，实际有 `28,764 / 49,152` 个元素超限，最大绝对误差为 `0.0015161633491516113`。因此当前只能声称 **HTP execution PASS、correctness FAIL**。
> - **主机构建已通过：**M0 真实图片 ROI 的选图、中心裁剪、`128×128 → 64×64 → 128×128`、bilinear/QNN 对照、PSNR 与本地图片证据代码已经通过 17 个 Java 单测、Android lint 和 debug assemble；尚未完成 Xiaomi 13 Ultra 真机运行、主机回读和人工画质审核。
> - **尚未实现：**完整图片分块、视频解码、Media3 effect、连续帧、播放器、实时 FPS、持续温控和可复用播放器 AAR。

这个独立仓库用于把现有 Android QNN HTP runtime 探针逐步变成可在手机上观察、评价并最终接入播放器的超分实验。它不是已经完成的播放器插件，也不把单个 `64×64` tensor 的执行时间包装成视频性能。

开发仓库：[znbsf/quicksr-mobile-player-lab](https://github.com/znbsf/quicksr-mobile-player-lab)（当前为 private、source-only；没有模型、APK 或原始真机证据）。

更细的当前状态见 [docs/STATUS.md](docs/STATUS.md)，播放器阶段和 Media3 技术路线见 [docs/PLAYER_ROADMAP.md](docs/PLAYER_ROADMAP.md)。

## 当前目标：M0 真实图片 ROI

M0 只处理用户本地选择的一张图片中的固定区域：

```text
系统选图器
→ 解码图片并取中心 128×128 HR reference
→ 确定性 2× 下采样为 64×64 LR
├─→ 确定性 bilinear 2× baseline
└─→ RGB float32 NCHW
    → QuickSRNetSmall ×2 fixed64 DCR
    → ORT + QNN HTP strict
    → 128×128 RGB output
→ 原图 / bilinear / QNN 三图显示
→ PSNR、模型/输入/输出 hash 与本地证据落盘
```

源码存在不等于 M0 已通过。M0 只有在同一 source/build/model/workload 的真机回执、四张输出图片、HTP trace、数值评价和人工复核全部关联后，才允许标记完成。

## 本地模型准备

模型和派生 ONNX 都是本地输入，不属于 GitHub 源码内容。构建前必须准备：

```text
models/quicksrnet-small-2x-opset17.onnx
derived-models/quicksrnet-small-2x-fixed64-core.onnx
derived-models/quicksrnet-small-2x-fixed64-dcr.onnx
```

canonical 模型的冻结身份：

```text
bytes:   93994
sha256:  3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce
```

把经过独立来源核验的 canonical ONNX 放入 `models/` 后，在包含 `onnx`、`onnxruntime` 和 `numpy` 的 Python 环境中运行：

```powershell
python .\derived-models\derive_quicksrnet_fixed64.py
python .\derived-models\test_derived_models.py
```

派生脚本会验证 canonical 模型与 P2 plan 的冻结 hash，并只在 `derived-models/` 写入固定 shape 模型。完整文件身份、许可边界和自定义模型路径见 [models/README.md](models/README.md)。

## 构建入口

本项目需要 Android SDK、Java 17+ 和 Gradle 8.14。仓库不会自动安装这些工具，也不会自动下载替代模型。

在仓库根目录运行：

```powershell
.\build-local.ps1
```

也可以只覆盖 canonical 模型位置：

```powershell
.\build-local.ps1 -ModelPath <local-canonical-onnx>
```

派生模型仍必须位于 `derived-models/` 的固定位置。构建脚本在 hash 或文件大小不匹配时 fail closed，并依次运行 Java unit tests、Android lint、debug assemble 和 source/build identity linkage。构建完成并不等于真机 M0 或 HTP 门禁通过。

本地 APK 生成后可人工安装到已授权、已解锁的目标手机进行实验；APK 是本地实验产物，不能提交到本仓库或据此推导公开再分发权利。

## 证据门禁

以下状态必须分别保存，不能合并成一个“成功”按钮：

| 证据轴 | 要回答的问题 | 当前状态 |
| --- | --- | --- |
| Build linkage | source、APK、模型、plan 与 receipt 是否绑定 | 主机构建 PASS；M0 真机 receipt linkage 待运行 |
| Runtime | ORT/QNN 是否初始化并完成冻结 workload | P3 PASS；M0 待运行 |
| Placement | 模型节点是否属于 QNN EP，是否出现 CPU EP compute | P3 PASS；M0 待验证 |
| Hardware | 是否存在 HTP architecture、RPC、accelerator/HVX 证据 | P3 PASS；M0 待验证 |
| Correctness | 输出是否通过运行前冻结的数值合同 | P3 FAIL；M0 合同与复测待完成 |
| Visual quality | 相对 HR 与 bilinear 是提升还是退化 | 未验收 |
| Performance | decode、preprocess、inference、render 和整帧各耗时多少 | 未建立发布级 benchmark |
| Playback | PTS、seek、flush、drop/backpressure 是否正确 | 播放器未实现 |
| Human review | reviewer 是否真实打开图片、回执与引用 | 未审核 |

profiling timing 只用于定位；`Session.run` timing 不是完整图像或视频 FPS。HTP execution PASS 也不能替代正确性或画质审核。

## Media3 播放器方向

未来目标是一个编译期接入的 Media3 effect library 和最小本地视频 demo，而不是可以动态安装到任意 mpv/VLC 的通用插件。路线按以下顺序推进：

1. M0：真实静态图片 ROI；
2. M1：Media3 passthrough effect、texture 生命周期与 PTS/flush；
3. M2：低分辨率完整视频帧进入 QNN HTP；
4. M3：tile/full-frame、画质、延迟、丢帧与持续温控；
5. M4：可复用 AAR、demo 和公开交付审计。

详细 API、颜色空间、GL texture/readback、背压和阶段门禁见 [Media3 路线图](docs/PLAYER_ROADMAP.md)。M1～M4 当前均未实现。

## Source-only GitHub 边界

本项目采用 **private-first、source-only**：即使 GitHub 仓库是 private，也只提交适合源码审查的材料。

当前 `main` 已推送到 owner-only 私有仓库；GitHub `source-safety` 会重复执行发布集合检查、合成负面用例、17 个 evidence-validator 测试和 JSON 合同解析。它不执行需要本地模型/vendor runtime 的 Android 构建，因此不能替代上文的本地主机构建证据。

允许进入 Git 的内容包括原创 Java/Python/PowerShell/JavaScript 源码、Gradle 配置、测试、验证器、冻结合同、hash、架构、问题记录和经过人工脱敏的小型汇总。

禁止进入 Git 的内容包括：

- checkpoint、ONNX 和其他模型权重；
- APK/AAB/AAR、Qualcomm/ORT `.so` 与其他编译二进制；
- 原始 tensor、receipt、ORT profile、QNN CSV/trace、Android 日志；
- 私人图片、视频、截图、EXIF、URI 或本机绝对路径；
- 凭据、签名材料和未确认可再分发的第三方内容。

发布规则见 [docs/PUBLICATION_BOUNDARY.md](docs/PUBLICATION_BOUNDARY.md) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。在独立 Git 仓库中选定待提交文件后，应先运行：

```powershell
.\scripts\verify-publication.ps1
```

自动检查 PASS 只说明候选文件没有命中已知泄漏规则，不授予模型、数据集、媒体、vendor binary 或 APK 的再分发许可。

## 当前不可声称

- M0 已在 Xiaomi 13 Ultra 真机通过；
- HTP 输出已经通过新的正确性合同；
- QuickSR 对真实动画画质已有提升；
- 完整图片、360p/540p/720p 或视频已完成；
- 已获得实时 FPS、功耗或持续温控结论；
- Media3 播放器或任意播放器插件已经实现；
- 当前模型权重或含权重 APK 可以公开再分发。

历史 P3 执行与失败边界见 [docs/P3_EVIDENCE_SUMMARY.md](docs/P3_EVIDENCE_SUMMARY.md)，当前独立仓问题过程见 [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md)。
