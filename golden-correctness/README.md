# Android ↔ PC golden correctness

这条门禁只回答一个问题：小米 13 Ultra 上某次已记录推理返回的
`1×3×128×128` float32 tensor，是否与同源模型、同输入的 PC CPU golden 在冻结
容差内一致。回执中的 backend 名称是谱系合同的一部分，但 correctness PASS 本身
不证明算子实际 placement 或没有 fallback。

## 为什么不能直接复用旧 `gradient-64`

旧资格实验的蓝色通道为 `(red + green) * 0.5`。Android 原型实际执行的是
`float(x + y) / float(width + height - 2)`。两者图像含义相同，但有 484 个
float32 元素相差 1 ULP，因此不是同一个字节级 workload。这里重新生成与 Java
实现完全相同的输入，并要求输入 SHA-256 为：

`cc13c100d394903d5c9ccde7a44aab63660e266099077063a0a0de326f5b9fc9`

## 运行

```powershell
& ./run-golden-gate.ps1
```

脚本先生成可复查的 PC golden，再检查 Android 回执。当前 P0 回执只保存输出
hash；PC 与 Android 的输出 hash 不同时，脚本必须返回 `INCOMPLETE`（进程退出码
2），不能把“hash 都存在”冒充容差正确性。

Android 导出原始 tensor 后，使用：

```powershell
& ./run-golden-gate.ps1 -AndroidOutput <output.f32le-or-output.raw.b64>
```

原始输出必须是连续 little-endian float32 NCHW，共 49,152 个元素、196,608
bytes。比较器先要求其 SHA-256 与设备回执一致，再执行：

`abs(android - pc_golden) <= 1e-4 + 1e-4 * abs(pc_golden)`

允许 mismatch 与 non-finite 数量均为 0。任何模型、输入、tensor hash、长度或
shape 不一致都会 fail closed。

## 产物边界

- `pc-golden-manifest.json`：模型、真实 Android 回执、运行时和容差合同。
- `android-input.f32le.raw.b64`：Java 等价输入的精确字节。
- `pc-golden-output.f32le.raw.b64`：PC CPU golden 的精确字节。
- `android-output.f32le.raw.b64`：通过 receipt hash 校验后保存的真机原始输出。
- `android-receipt.json.raw.b64`：真机回执的原始字节，避免换行转换破坏 hash。
- `android-vs-pc-comparison.json`：最终 PASS/FAIL；缺设备 tensor 时为 INCOMPLETE。
- `validation-result.json`：从四份原始 artifact 重新计算 hash linkage 与容差指标。

对已完成案例做不重新推理的独立复算：

```powershell
<frozen-python> ./validate_golden_case.py --case-dir ./results/xiaomi-13-ultra-p1-20260830 --no-write
```

`--no-write` 会打印本次独立复算结果，但不会刷新冻结案例中的
`validation-result.json`。带时间戳的 validation artifact 属于 promotion 产物；日常复核
必须使用只读模式，否则会使引用它的耐久案例 hash 失效。

## P2 派生模型

P2 `fixed64-pre-shuffle-core` 和 `fixed64-dcr-full` 都不能只凭真机回执自报的一个
manifest hash 继承 canonical golden。比较器会读取真实 `derivation-manifest.json`，
并同时核查：

- canonical source、derived model bytes/SHA/variant 与 P2 plan SHA；
- PC 上派生图对 canonical 的冻结等价性；
- core 变体的 `1×12×64×64` session output 与应用 CRD pixel shuffle 合同；
- full DCR 变体的 `1×3×128×128` model output 且没有应用侧 postprocess；
- 最终 `1×3×128×128` artifact 与 canonical PC golden 的逐元素容差。

通用入口要求显式指定结果目录，防止不同派生变体写入同一个 case：

```powershell
& ./run-p2-derived-golden-gate.ps1 `
  -AndroidReceipt <DERIVED-PASS.json> `
  -AndroidOutput <DERIVED-PASS.output.f32le> `
  -ResultDirectory ./results/<unique-derived-case>
```

结果目录必须为空；已有案例视为冻结证据，脚本会 fail closed。需要重新 promotion 时
使用新的唯一目录，而不是覆盖旧案例。

旧的 core 入口仍兼容，并保留原默认目录参数；由于原案例已冻结，实际新运行应显式
传入一个新的 `-ResultDirectory`：

```powershell
& ./run-p2-core-golden-gate.ps1 `
  -AndroidReceipt <XNNPACK_CORE_HYBRID-PASS.json> `
  -AndroidOutput <XNNPACK_CORE_HYBRID-PASS.output.f32le> `
  -ResultDirectory ./results/<unique-core-case>
```

core 默认结果目录为
`results/xiaomi-13-ultra-p2-xnnpack-core-hybrid-20260830/`。脚本不调用 ADB、
不安装 APK，也不启动推理；它只消费已经拉回主机的证据。结果目录会自包含复制
PC manifest/input/golden、原始 device receipt/output 和 derivation manifest，随后由
`validate_golden_case.py` 独立复算。

2026-08-30 的真实小米 13 Ultra case 已由 comparator 和独立 validator 双重 PASS：
49,152 个元素，`mismatch=0`、`nonfinite=0`、最大绝对误差
`1.1324882507324219e-06`、最大相对误差 `0.0001046135144296518`、平均绝对误差
`1.3592213458461325e-07`。这是 correctness 结论，不等同于无 fallback 或性能结论。

真实 `NNAPI_DCR_HYBRID` unlocked case 位于
`results/xiaomi-13-ultra-p2-nnapi-dcr-hybrid-unlocked-20260830/`，也已由 comparator
和独立 validator 双重 PASS：49,152 个元素，`mismatch=0`、`nonfinite=0`、最大绝对
误差 `8.940696716308594e-07`、最大相对误差 `4.143672423860019e-05`、平均绝对误差
`9.527979708915761e-08`。`unlocked` 表示这次探针允许回执成功落盘；该 correctness
结果不能单独证明 NNAPI 节点实际执行，仍需独立 profiling/placement 证据。

PC golden 是锁定 ONNX 模型的跨平台参考，不替代上游 PyTorch→ONNX 资格验证。
`compare_android_output.py` 也可直接检查使用同模型、同输入并导出 raw tensor 的
hybrid/XNNPACK/NNAPI/QNN 成功回执；但数值 PASS 只证明输出正确，不能证明节点
placement、无 fallback 或目标硬件实际执行，这些仍需独立 profiling 证据。
