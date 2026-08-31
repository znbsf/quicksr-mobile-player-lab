# Local model placement

本目录只用于个人本地实验中的 canonical QuickSRNetSmall ×2 ONNX。模型权重不进入 Git，也不随 source-only GitHub 仓库或 APK 发布。

## 必需文件

将经过独立来源核验的 canonical 模型放在：

```text
models/quicksrnet-small-2x-opset17.onnx
```

冻结身份：

| 字段 | 值 |
| --- | --- |
| bytes | `93994` |
| SHA-256 | `3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce` |
| input | float32 NCHW `[batch, 3, height, width]` |
| output | float32 NCHW `[batch, 3, 2×height, 2×width]` |

构建和派生脚本都会在文件大小或 SHA-256 不匹配时 fail closed。不要重命名另一个模型来绕过验证，也不要修改脚本中的冻结 hash 以适配未知文件。

可以本地检查：

```powershell
Get-Item .\models\quicksrnet-small-2x-opset17.onnx | Select-Object Length
Get-FileHash .\models\quicksrnet-small-2x-opset17.onnx -Algorithm SHA256
```

## 派生模型

Android P3/M0 的 QNN HTP 路径使用 fixed-shape DCR 模型。派生文件不放在 `models/`，而由可审查脚本写入：

```text
derived-models/quicksrnet-small-2x-fixed64-core.onnx
derived-models/quicksrnet-small-2x-fixed64-dcr.onnx
```

冻结身份：

| 文件 | bytes | SHA-256 | 用途 |
| --- | ---: | --- | --- |
| `quicksrnet-small-2x-fixed64-core.onnx` | `93809` | `9a35f235ac9dc36447764a58a2d1511720dc346360f76b77fee490b347f9e3b6` | 图外 CRD PixelShuffle 诊断 |
| `quicksrnet-small-2x-fixed64-dcr.onnx` | `93923` | `c902565d3ec55de1fbfa66aac8e283890c7b77eab0e39c60ba35022691148a5f` | QNN HTP strict 的 `64×64 → 128×128` 主路径 |

在包含 `onnx`、`onnxruntime` 和 `numpy` 的 Python 环境中运行：

```powershell
python .\derived-models\derive_quicksrnet_fixed64.py
python .\derived-models\test_derived_models.py
```

派生脚本会验证 canonical ONNX、P2 plan、图结构、权重身份和变换边界，然后原子写入两个本地 ONNX 与 manifest。派生模型仍包含完整 trained weights；图变换不会产生新的再分发许可。

## 构建使用方式

默认构建入口从本目录读取 canonical 模型：

```powershell
.\build-local.ps1
```

也可以通过参数使用另一个本地位置，但文件身份必须完全相同：

```powershell
.\build-local.ps1 -ModelPath <local-canonical-onnx>
```

这个参数只覆盖 canonical 模型。两个 fixed-shape 派生模型仍必须存在于 `derived-models/`。

一个全新的 source-only checkout **不会包含模型，也不能直接 assemble**。这是预期的 fail-closed 行为，不是仓库缺失文件的 bug。

## 来源与许可边界

本仓库保存来源 URL、版本、hash、派生代码和验证方法，但不把上游代码许可证自动解释为 checkpoint、训练数据或 APK 的再分发授权。

当前保守边界：

- canonical、fixed-core 和 fixed-DCR ONNX 均只在本机保存；
- 不使用 `git add -f` 绕过 `*.onnx` ignore；
- 不将模型打包后的 APK/AAB/AAR 上传到 GitHub Releases；
- 不提交训练/评测图片、私人媒体或 raw inference evidence；
- 若未来需要公开或商业分发，必须先完成独立的模型权利审核。

第三方来源和版本记录在仓库根目录的 `THIRD_PARTY_NOTICES.md`；完整发布策略在 `docs/PUBLICATION_BOUNDARY.md`。
