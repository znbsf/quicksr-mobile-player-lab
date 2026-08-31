# Golden correctness 问题日志

## GOLD-P001 — 旧 `gradient-64` 与 Android 输入不是同一字节序列

- 现象：两边的红、绿通道一致，但旧 PC 生成器以 `(red + green) * 0.5`
  计算蓝色通道，Android 以 `float(x + y) / float(126)` 计算。
- 证据：共有 484 个 float32 元素相差 1 ULP，最大绝对差
  `5.960464477539063e-08`；旧输入 hash 为 `6e070f...`，Android 输入 hash
  为 `cc13c1...`。
- 处理：新增 Java 算术等价生成器，并以真机回执中的 `cc13c1...` 做硬门禁；
  不复用旧 tensor。
- 状态：已解决。

## GOLD-P002 — PC 与 Android 输出 hash 不同，单靠 hash 不能算容差

- 现象：精确 Android 输入在 PC ORT 1.22.1 CPU 上得到输出 hash `efc9b2...`，
  Android ORT 1.26.0 ARM CPU 回执为 `14f1b5...`。
- 影响：只能确认不是 byte-exact；不能由两个 hash 推导逐元素误差，也不能判定
  它是否超出 `atol=rtol=1e-4`。
- 处理：比较器在没有 Android 原始 tensor 时返回 `INCOMPLETE` 和退出码 2；
  Android 侧需导出 196,608-byte little-endian float32 tensor。
- 复测：P1 真机输出已导出并通过 receipt hash、长度、dtype、byte order、shape
  和逐元素容差检查；49,152 个元素 `mismatch=0`、`nonfinite=0`、最大绝对误差
  `1.0728836059570312e-06`、最大相对误差 `2.4749542365493198e-05`。
- 状态：已解决，Android CPU golden correctness PASS。

## GOLD-P003 — 首次读取 Base64 golden 因尾随换行失败

- 现象：生成器按文本文件惯例写入尾随换行，而比较器使用严格 Base64 校验，
  首轮报 `invalid base64 tensor artifact`，尚未进入 Android 比较。
- 处理：解码前只移除空白字符，仍保留 `validate=True` 对非 Base64 字符
  fail closed；同时补充 non-finite 指标的 JSON 安全处理。
- 状态：已解决并复测。

## GOLD-P004 — 一次非必要的 C++ hash 探索命令被安全边界拒绝

- 现象：为判断 Windows ORT C++ 1.26 输出能否与 Android byte-exact，曾把临时
  目录创建、生成 runner input、运行与递归清理组合在一个命令中；执行环境因
  复合递归清理目标未独立验证而拒绝该命令。
- 影响：命令未启动、没有文件被创建或删除，也不影响 Python PC golden 与
  Android tensor 的正式容差门禁。
- 处理：没有放宽安全策略，也没有重复该非必要探索；改用已冻结 PC ORT golden
  与真机原始 tensor 完成逐元素比较。
- 状态：已关闭，不作为 correctness 证据。

## GOLD-P005 — 负面测试的预期非零退出被 PowerShell 提前升级

- 现象：首次把正向测试、hash-link 负面测试和旧回执 `INCOMPLETE` 测试放在同一
  严格脚本中；`ErrorActionPreference=Stop` 在 Python 按预期返回 1 时先终止，
  未执行后面的显式 exit-code 断言。
- 影响：正向 P1 比较已经 PASS，但该轮组合验证没有完整跑到末尾；不是模型或
  comparator 失败。
- 处理：仅在预期非零的两段暂时使用 `Continue`，随后恢复 `Stop` 并分别硬断言
  hash mismatch=`1`、缺 raw tensor=`2`；完整验证复跑通过。
- 状态：已解决。

## GOLD-P006 — `__pycache__` 清理被执行安全策略拒绝

- 现象：验证后尝试清理 Python bytecode 缓存；即使先检查目标位于
  `golden-correctness` 内，执行环境仍拒绝了递归和逐文件删除命令。
- 影响：没有源码或证据被删除；只留下本机生成的 `.pyc` 缓存。
- 处理：停止重复删除，使用 `git check-ignore -v` 确认根 `.gitignore` 的
  `__pycache__/` 规则已生效；这些文件不会进入仓库证据。
- 状态：环境限制已确认，不阻断交付。

## GOLD-P007 — 派生模型回执原先只有自报 hash，没有强谱系复核

- 现象：旧比较器允许回执以 `derived=true`、canonical source hash 和一个格式正确的
  manifest hash 声明派生关系，但不会读取 manifest，也不会核对 P2 plan、派生模型
  identity、core output contract、应用侧 CRD pixel shuffle 或 PC 派生等价性。
- 风险：互相一致但并非真实 P2 合同的回执字段可能越过 correctness gate；原独立
  validator 还会把所有非 canonical model 直接判失败，导致比较器与复核器语义冲突。
- 处理：增加必需的 `--derivation-manifest`；对 P2 core 的 receipt、backend、plan、
  model、session output、raw model output、postprocess、final output 和 PC ORT 等价证据
  做 fail-closed 复核，并在 case 内保存原始 manifest、receipt、Android output 与完整
  PC golden bundle。独立 validator 从这些副本重新计算派生谱系和逐元素指标。
- 复测：正向派生 core fixture、manifest byte drift 负例、自包含副本和独立 validator
  的端到端测试均通过；测试不调用 ADB 或设备推理。
- 复测：真实小米 13 Ultra `XNNPACK_CORE_HYBRID` case 已通过 comparator 与独立
  validator；49,152 个元素 `mismatch=0`、`nonfinite=0`，最大绝对误差
  `1.1324882507324219e-06`，derived manifest/P2 plan linkage 为 PASS。
- 状态：已解决并完成真实 P2 core golden correctness 门禁。

## GOLD-P008 — exact-hash 分支收到 raw output 时没有保存它

- 现象：新增派生端到端 fixture 的 final output 与 PC golden byte-exact，比较器走
  `exact-output-byte-hash` 快路径；虽然命令提供了 `--android-output`，结果却没有
  `androidOutput` 证据，独立 validator 以 `KeyError: androidOutput` fail closed。
- 处理：在 exact/hash 分支判断前统一读取、hash-link 并保存显式提供的 raw output；
  exact 分支也写入完整容差指标。
- 复测：同一端到端 fixture 随后通过 comparator 和独立 validator。
- 状态：已解决。

## GOLD-P009 — P2 正式比对前的首个 hash 清单命令有 PowerShell 语法错误

- 现象：只读预检把 `foreach` 语句直接接到 pipeline，PowerShell 报
  `An empty pipe element is not allowed`；该命令在读取证据前即失败。
- 影响：没有改写文件、没有触发 ADB 或推理，也没有运行 comparator。
- 处理：按最小重试边界，把 `foreach` 包在子表达式后重跑；receipt、raw output 和
  derivation manifest 的文件存在性、长度与 SHA-256 随后成功回读，再执行正式门禁。
- 后续：双 case 收尾汇总曾重复使用同一错误写法并再次被 parser 拒绝；仍未读取或
  改写证据，按同一修复单次重试后成功回读两个 PASS 结果。后续汇总命令不得把
  PowerShell statement 直接接到 pipeline。
- 状态：已解决，不影响 P2 correctness 结论。

## GOLD-P010 — 派生门禁入口名称和默认目录只表达 core，无法安全复用到 DCR

- 现象：`run-p2-core-golden-gate.ps1` 内部实现实际上可消费 comparator 支持的任意
  P2 派生回执，但脚本名和默认结果目录固定为 XNNPACK core；直接拿它跑 DCR 容易
  产生语义误导或覆盖 core case。
- 处理：抽出 `run-p2-derived-golden-gate.ps1`，对 receipt、raw output 和唯一结果
  目录都使用明确参数；PC golden 与 derivation manifest 仍有冻结默认值。旧 core
  入口改为兼容转发器，保留原参数和默认目录。
- 复测：真实 `NNAPI_DCR_HYBRID` unlocked case 的 comparator 与独立 validator 均
  PASS；49,152 个元素 `mismatch=0`、`nonfinite=0`，最大绝对误差
  `8.940696716308594e-07`，派生 manifest/P2 plan linkage 为 PASS。
- 边界：该结果只证明 full-DCR 最终 tensor correctness，不把 unlocked/hybrid 回执
  名称冒充为 NNAPI 节点 placement 证据。
- 状态：已解决。
