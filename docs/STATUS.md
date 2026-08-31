# Project status

状态日期：2026-08-31

本文件只记录已经观察到的事实、当前实现状态和下一门禁。源码存在、构建通过、真机执行、正确性、画质、性能和人工审核是不同状态。

## 一句话结论

P3 已证明 Xiaomi 13 Ultra 上的 QNN HTP strict 执行资格，但冻结 PC golden 正确性合同 FAIL；独立仓库正在实现 M0 真实图片 ROI，尚未形成绑定本仓库 source/build 的真机结果；播放器未实现。

## 阶段状态

| 阶段或门禁 | 状态 | 可证明的事实 | 仍然缺少 |
| --- | --- | --- | --- |
| P3 QNN HTP runtime | PASS（历史真机证据） | Xiaomi 13 Ultra 枚举到 Qualcomm NPU，使用 HTP V73，禁 CPU EP fallback，35/35 ORT model events 仅 QNN，11/11 QNN ops supported，并存在 RPC/accelerator/HVX trace | 该结果不是本独立仓库 M0 的真机验收 |
| P3 PC golden correctness | FAIL | `28,764 / 49,152` mismatch；max absolute error `0.0015161633491516113`；原冻结合同 `atol=rtol=1e-4` 且允许 mismatch 为 0 | 不得事后放宽 P3 合同；新合同必须在新 workload 运行前冻结 |
| 独立源码白名单 | PRESENT | Android/ORT/QNN 源码、派生工具、验证器和文档已被选择性放入独立目录 | 尚未由本文件声称已经提交或推送到 GitHub |
| M0 选图与 ROI 源码 | HOST BUILD PASS | 已有系统选图、中心 ROI、确定性下采样、bilinear、RGB/NCHW、QNN 输出转 Bitmap、PSNR 和本地 PNG 证据代码；17 个 Java 单测、lint、assemble 通过 | 仍需真机运行、回执回读、host validation 和人工审核 |
| M0 build linkage | HOST PASS / DEVICE PENDING | wrapper 构建已产生绑定新 application ID 和 source identity 的本地 debug APK；APK 不进入 Git | 尚无本独立仓真机 receipt 来关闭 device linkage |
| M0 Xiaomi 13 Ultra | NOT RUN / NOT REPORTED | 无 | 真实图片、HTP strict 回执、四张图片、trace 与失败记录 |
| M0 correctness/quality | PENDING | P3 的精度错误类别已有记录 | 运行前冻结的 M0/P4 数值与画质合同、未参与定阈值的 ROI、PSNR/SSIM 或有依据的替代指标、人工 A/B |
| Full-image tile/stitch | NOT IMPLEMENTED | 只有 fixed `64×64 → 128×128` 模型路径 | tile、halo、padding、crop、stitch、seam 与内存验证 |
| Media3 M1～M4 | NOT IMPLEMENTED | 已有文档化路线 | Media3 依赖、播放器壳、effect、texture/PTS/flush、连续帧、AAR 与真机证据 |
| Publication | NOT COMPLETE | 已有 source-only 规则和自动扫描脚本 | 独立 Git 初始化、候选文件审计、提交、private remote push 与回读 |

## P3 已经允许的声明

允许：

> 在冻结的 `64×64 → 128×128` fixed-DCR workload 上，Xiaomi 13 Ultra 已完成 QNN HTP strict 执行资格验证，且该次观察没有 CPU EP compute fallback。

这句话必须同时附带：

> 同一 P3 运行未通过冻结的 PC golden 正确性合同，因此不能声称 HTP correctness 完成。

P3 profiling 的单 tile timing 只用于执行路径诊断，不能换算为整图或视频 FPS。

## M0 当前代码范围

当前 M0 源码意图实现：

```text
本地图片
→ 中心 128×128 HR ROI
→ 固定 2× average downsample 为 64×64 LR
→ deterministic bilinear baseline
→ QuickSRNet fixed64 DCR + QNN HTP strict
→ 128×128 QNN Bitmap
→ PSNR 与四张 PNG evidence
```

当前 UI 或代码中出现“完成”文字不能充当验收。机器状态在真机回执和主机验证前保持 pending，human review 在 reviewer 打开真实图片和引用前保持 pending。

## M0 关闭条件

M0 只有全部满足下列条件后才能改为 PASS：

1. 构建脚本通过并记录 source/build/APK/model/plan identity；
2. Xiaomi 13 Ultra 上从系统选图器完成一次真实图片 ROI；
3. `reference-hr-128.png`、`input-lr-64.png`、`baseline-bilinear-128.png` 和 `qnn-htp-128.png` 均保存并 hash；
4. QNN HTP strict、CPU fallback disabled、ORT placement 和底层 HTP trace 分别通过；
5. 运行前冻结的数值/画质合同在未用于定阈值的 ROI 上复测；
6. 失败、非有限值、颜色通道、clamp、PNG 编码和 source/build 漂移均 fail closed；
7. 主机验证器回读通过；
8. 人工 reviewer 打开四张图片和 receipt，单独记录审核结论。

## 下一步顺序

1. 完成 standalone Golden 合成 fixture 回归与发布集合检查；
2. 实现并冻结 P4 SSIM 阈值与三组输入 source identity/hash；
3. 在已解锁的 Xiaomi 13 Ultra 上运行一张权利清晰或私人本地图片；
4. 回读并独立验证 receipt、输出图片、ORT profile 和 QNN trace；
5. 保留 PASS 或 FAIL，不根据结果事后修改合同；
6. 完成人工 A/B 后再决定是否进入 Media3 M1。

播放器路线及其颜色空间、GL readback、PTS、背压与持续性能门禁见 [PLAYER_ROADMAP.md](PLAYER_ROADMAP.md)。发布边界见 [PUBLICATION_BOUNDARY.md](PUBLICATION_BOUNDARY.md)。

## 当前禁止的声明

- “真实图片超分已经在手机完成”；
- “QNN HTP 正确性已经通过”；
- “神经超分优于 bilinear/Anime4K”；
- “完整图片已经无缝分块”；
- “播放器插件已经实现”；
- “720p/1080p 实时”；
- “APK 或模型可以在 GitHub 发布”。
