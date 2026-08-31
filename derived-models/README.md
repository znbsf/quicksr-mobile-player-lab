# QuickSRNetSmall ×2 fixed64 派生模型

本目录保存可审查的派生工具、测试和 manifest；含完整权重的 ONNX 只在本机生成，不进入 Git。

## 跟踪边界

| 文件 | Git 边界 | 原因 |
|---|---|---|
| `README.md` | 可跟踪 | 复现与许可边界 |
| `derive_quicksrnet_fixed64.py` | 可跟踪 | 确定性图变换与 PC ORT 验证代码，不含模型权重 |
| `test_derived_models.py` | 可跟踪 | 图结构、hash、PixelShuffle 和数值等价单测，不含模型权重 |
| `derivation-manifest.json` | 可跟踪 | 来源/输出 hash、shape、算子、变换与验证元数据，不含模型权重 |
| `quicksrnet-small-2x-fixed64-core.onnx` | **仅本地生成** | 包含从上游 checkpoint 派生的完整 initializer 权重 |
| `quicksrnet-small-2x-fixed64-dcr.onnx` | **仅本地生成** | 包含从上游 checkpoint 派生的完整 initializer 权重 |
| `__pycache__/` | 仅本地生成 | Python 缓存 |

不要使用 `git add -f` 绕过 ONNX 忽略规则。根目录的 `*.onnx` 规则会阻止误加；canonical ONNX、checkpoint 和其他本地产物也保持在 Git 之外。

`derivation-manifest.json` 是可复核的身份和验证记录，不代表模型文件已存在，也不是模型权重的再分发授权。

## 本地复现

前提：仓库根目录的 `models/quicksrnet-small-2x-opset17.onnx` 已由资格验证流程准备，且 hash 与派生脚本的冻结值一致。在仓库根目录执行：

```powershell
$env:PYTHONDONTWRITEBYTECODE = "1"
python .\derived-models\derive_quicksrnet_fixed64.py
Push-Location .\derived-models
try { python -m unittest .\test_derived_models.py } finally { Pop-Location }
```

派生脚本不下载模型、不接受路径覆盖；canonical ONNX 或 P2 plan 的 hash 不匹配时会失败关闭。成功后生成：

- `quicksrnet-small-2x-fixed64-core.onnx`：固定输入 `[1,3,64,64]`，输出 `pre_shuffle_output [1,12,64,64]`，应用侧执行 CRD PixelShuffle。
- `quicksrnet-small-2x-fixed64-dcr.onnx`：固定输入 `[1,3,64,64]`，输出 `upscaled_image [1,3,128,128]`，图内使用 DCR DepthToSpace。
- `derivation-manifest.json`：记录 source/P2 plan/tool/output hash、图合同、变换和 PC ORT 等价结果。

以 manifest 中的 hash 和验证状态为当前事实源，不手工复制旧 hash。

## 来源与许可边界

- Qualcomm AI Hub Models 和 AIMET Model Zoo 仓库在本实验锁定的 revision 中使用 BSD-3-Clause；其二进制再分发条款要求在相关文档或材料中保留版权、条件和免责声明。
- AIMET Model Zoo 的 QuickSRNet model card 明确说明 checkpoint 在 DIV2K 上训练。
- DIV2K 官方页面说明数据集仅供学术研究，图片版权属于原始权利人。
- 当前来源证据没有给该 release 中每个 checkpoint 单独、明确的商业权重许可声明。因此，本仓库不从代码仓库的 BSD 许可证或数据集访问权限推导模型权重的商业分发许可。

基于上述不确定性，本项目只把这些模型用于个人研究和可复现实验：提交派生代码、测试、manifest 与来源记录；canonical checkpoint/ONNX 和两个派生 ONNX 均保持本地。派生图变换不会产生一份脱离原权重来源的新许可资产。

上游一手资料：

- [Qualcomm AI Hub Models license](https://raw.githubusercontent.com/qualcomm/ai-hub-models/5975a79b55b40f5cbc61f3ac5e52abe47d9d8bd5/LICENSE)
- [AIMET Model Zoo license](https://raw.githubusercontent.com/quic/aimet-model-zoo/1bd2bf5b17cdda9251437c444009b29e1a25054b/LICENSE.md)
- [QuickSRNet model card](https://raw.githubusercontent.com/quic/aimet-model-zoo/1bd2bf5b17cdda9251437c444009b29e1a25054b/aimet_zoo_torch/quicksrnet/QuickSRNet.md)
- [DIV2K official dataset page](https://data.vision.ee.ethz.ch/cvl/DIV2K/)

本说明是项目的保守发布边界，不是法律意见；若未来需要公开或商业分发权重，应单独取得并保存适用的权利确认。
