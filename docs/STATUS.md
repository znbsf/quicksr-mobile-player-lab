# Project status

状态日期：2026-09-05

## 一句话结论

QuickSR 1080p 的平均处理吞吐已从不到 20fps 提升到 30fps，但最终屏幕节奏仍偶发一个
长帧后补短帧；所以当前可以说“30fps 吞吐通过”，不能说“保证原片帧率”。帧率硬门完全
关闭前，画质、换模型和插帧继续后置。

## 当前硬门

| 门禁 | 状态 | 已确认 | 未确认 |
| --- | --- | --- | --- |
| 主机构建 | `PASS` | Java/Python、lint、debug APK、androidTest APK 均通过 | 全新 checkout 仍需合法模型/vendor 依赖 |
| 真机 QNN 功能 | `DEVICE_BOUNDED PASS` | HTP 配置、CPU EP fallback 禁用、输出数值/ownership 仪器测试 | per-node placement trace、其他设备 |
| 1080p 30fps 处理吞吐 | `PASS` | 828 稳态帧，30.0045fps，PTS 覆盖约 1，drop/bypass 0 | 不是 GPU completion 或光子显示 |
| 1080p 最终显示 | `FAIL / ACTIVE` | 原画 ABBA 2/2 PASS；QNN 1/2 PASS | QNN 失败轮有 58.153ms 长间隔和两个补偿短间隔 |
| A/V sync | `OPEN` | 无 | 当前冻结 30fps 片源无音轨 |
| thermal / power | `OPEN` | 既有短时观察不作为正式门 | 当前默认架构的 10～30 分钟数据 |
| 1080p 动漫画质 | `OPEN / DEFERRED` | 有主机夹具与历史小语料 | 帧率门后同源同帧盲审、真实动漫代表性 |
| cadence 一拍二/三 | `IMPLEMENTED / OFF` | benchmark-only analyzer 与冻结映射 A/B 已有 | 真实字幕/切镜误复用、最终显示 |
| Anime4K | `IMPLEMENTED / DEVICE FUNCTION` | GPU-resident 播放器路径已接入 | 同帧质量、GPU timing、长时 |
| 插帧 | `OFFLINE ONLY` | RIFE/IFRNet 候选 CLI 探针已有 | 播放器接线、A/V sync、时序画质、thermal |
| AAR | `NOT IMPLEMENTED` | App 内部 effect/runtime 可用 | 稳定 API 与兼容矩阵 |

## 最终默认架构

```text
Media3 GL -> CPU NCHW input -> QNN HTP
          -> two pinned NHWC output slots
          -> deferred copy + four-stripe RGBA pack
          -> direct Media3 output texture upload
          -> SurfaceFlinger
```

- overlap：默认 ON；
- float32 NHWC 1080p 输出：默认 ON；
- deferred output copy：默认 ON，增加约 23.73MiB；
- 四条带 pack：默认；
- direct output texture upload：同尺寸档默认；
- PBO upload：默认 OFF；
- native packer、cadence reuse：默认 OFF。

## 最新物理机数字

最终 APK：`realtime-1080p-final-direct`，SHA-256
`deb5ef03ce97588625232d56e585b2abdd3810e903db2da38df495b6293260c8`。

### 30fps effect throughput

| 指标 | 结果 |
| --- | ---: |
| measured frames | 828 |
| observed fps | 30.0045 |
| source PTS coverage | 1.00000002 |
| inference caller p50 / p95 | 27.884 / 30.736ms |
| ORT run p95 | 30.244ms |
| output pack p50 / p95 | 11.321 / 15.503ms |
| GL upload submit proxy p95 | 6.987ms |
| accepted -> output-submit proxy p95 | 179.024ms |
| dropped / bypassed | 0 / 0 |
| max bounded queue depth | 2 |

`179.024ms` 是流水线多帧延迟，不是单帧服务时间；流水线填满后仍可按 30fps 交付。

### SurfaceFlinger actual-present ABBA

| 顺序 | 路径 | 状态 | actual-present fps | 长/短异常 |
| --- | --- | --- | ---: | ---: |
| A1 | Original | PASS | 29.9792 | 0 / 0 |
| B1 | QuickSR QNN | FAIL | 30.0341 | 1 / 2 |
| B2 | QuickSR QNN | PASS | 30.0030 | 0 / 0 |
| A2 | Original | PASS | 29.9544 | 0 / 0 |

失败轮的平均 fps 没下降，但出现 58.153ms 的最大间隔。因此缺陷是尾部节奏，不是当前平均
吞吐。SurfaceFlinger 指标仍只是 layer actual-present 代理，不是光子时序。

## 已采用和已停止的优化

| 候选 | 裁决 |
| --- | --- |
| NHWC + 4 条带 | 采用；2 条带更慢 |
| 双 pinned ORT output / deferred copy | 采用；ABBA 将 inference p95 平均降低约 4.2ms |
| 首帧完整 finite scan | 采用；停止稳态周期全扫造成的 330～353ms 停顿 |
| direct Media3 output texture | 采用；删除同尺寸档中间纹理与 blit |
| 双 PBO upload | 停止默认化；GL p95 更低但 SurfaceFlinger 仍 1/2 PASS，另增约 15.82MiB |
| JNI/NEON / direct FloatBuffer pack | 停止；真机明显更慢 |
| 扩队列、多加同 session inference worker | 不做；增加积压而不提高真实服务率 |

## 当前主要缺陷与下一步

主要缺陷只有一个：QNN 路径偶发错过最终 latch，尚不能保证逐帧稳定 30fps。下一步不是再
堆测试，而是捕获一份能将失败 frameId/PTS 与 Perfetto sched/freq、GL fence、BufferQueue、
SurfaceFlinger FrameTimeline 对齐的 trace。定位为 result-ready、GL/fence、latch 或 OS 调度
中的一类后，只实现对应的一条路径，再跑一次 A/B/B/A。

详细顺序见 [IMPLEMENTATION_PLAN_AND_PROGRESS.md](IMPLEMENTATION_PLAN_AND_PROGRESS.md)，
架构与负结果见 [REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md](REALTIME_ARCHITECTURE_OPTIMIZATION_AUDIT.md)，
机器可读证据见
[realtime-1080p-physical-20260905.json](evidence/realtime-1080p-physical-20260905.json)。

## 设备与发布边界

- 物理测试命令显式绑定单台授权手机；没有调用已连接电视。
- 硬件无关检查使用独立 `QuickSR_Isolated_API_35`；没有使用其他任务的 emulator。
- 模拟器结果不计入 QNN HTP 性能证据。
- 原始日志、媒体、APK、URI、设备序列号留在 Git 忽略目录；发布证据已去标识。
- 不自动 push；本地提交不等于发布。
