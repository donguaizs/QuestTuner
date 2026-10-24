# QuestTuner Round 5 Bug 报告（性能与代码质量审查）

- 审查人：David（QA）
- 日期：2026-10-24
- 基线：master @ fd7a777（round-4 已合入）
- 本轮视角：Compose 性能 / ADB 通道设计债 / 死代码残留 / 依赖健康。不重复 round-1~4 已核对项与在案项（R4-O7 延后、重定向逐跳可选、generation 单测边界）。
- 编号自 BUG-034 起。**本轮无 P0/P1**——性能视角无致命项（单用户本地工具、列表规模中等）；5 项 P2 均为实害可量化的性能/质量问题。

## 汇总

| 编号 | 级别 | 位置 | 摘要 |
|---|---|---|---|
| BUG-034 | P2 | TunerApp.kt:32 + TunerViewModel.kt:440 | 单一 UiState 高频发射（下载进度每百分点、连接过程 note 流）→ collectAsState 处全树重组：8 个 Card 全部重执行，且 InstalledApp 含 Drawable 不稳定类型放大跳过失效 |
| BUG-035 | P2 | AdbConnection.kt:74-79 | shell 输出 ByteArrayOutputStream 无总量上限——单包 1MB 检查不覆盖累计，异常大输出命令可 OOM |
| BUG-036 | P2 | AdbConnection.kt:31-71 | connect() 失败不自清理（socket 泄漏面依赖唯一调用方 close 纪律）+ 同实例二次 connect 覆盖旧 socket——类自身资源语义不自洽 |
| BUG-037 | P2 | TuningCommands.kt:20-49 + AdbProtocol.kt:41-71 | 死代码群：4 个零调用 TuningCommands 函数（round-1 至今）+ AdbProtocol decodeHeader/verifyCrc/decodeFull 无生产调用方（连测试都没有） |
| BUG-038 | P2 | build.gradle.kts:62-65 | datastore-preferences 零使用（round-2 PLAN 挂起决策至今 5 轮）+ lifecycle-runtime-ktx 冗余（核心 API lifecycleScope/repeatOnLifecycle 全程未用，viewModelScope 来自传递依赖） |

优化建议（R5-O1~O6）见专门章节。

---

## P2

### BUG-034 单一 UiState 高频发射导致全树重组；不稳定类型放大跳过失效

- 级别：P2（性能实害可量化：一次 2MB 下载 ≈100 次全树重组）
- 位置：`ui/TunerApp.kt:32`（唯一 collectAsState，顶层）；发射源 `ui/TunerViewModel.kt:440`（updateProgress 每百分点 copy 一次）、`:111`（连接过程 onNote 流）
- 描述：UiState 是 17 字段大杂烩（连接态/应用列表/profiles/更新进度/自动化开关……），`TunerApp` 顶层一次 `collectAsState` 后把 `state` 整体传给 8 个 Card。**任何字段变化都使 UiState 不等 → collectAsState 重组 TunerApp → 8 个 Card 的 content lambda 全部重新执行**（参数 state 已变，skippable 全部失效）。高频源实证：
  1. 下载进度：`download` 的 onProgress 每百分点回调 → `_state.value = copy(updateProgress = p)`（:440）→ 一次 2MB 下载约 **100 次**全树重组（ConnectionCard/StatusCard/QuickTune/AutomationCard/全部 ProfileRow/NewProfileCard/LogCard 陪跑）；
  2. 连接过程 onNote 流（:111）与 setAutoA11y 的 waitA11yRunning 轮询回填同理。
  放大因素：`InstalledApp` 含 `Drawable?` 字段（不稳定类型）→ `List<InstalledApp>` 传染不稳定 → 即使 Compose 想跳过 AppPickerDialog 相关组合也难以判定稳定。
- 证据：
  ```kotlin
  // TunerApp.kt:32 —— 顶层单一订阅
  val state by viewModel.state.collectAsState()
  // TunerViewModel.kt:440 —— 每百分点一次全量 copy
  SelfUpdater.download(info.url, info.size, apk) { p ->
      _state.value = _state.value.copy(updateProgress = p)
  }
  // TunerApp.kt:36-55 —— 8 个 item 全部以 state 为参数
  item { ConnectionCard(state, viewModel) } ... item { LogCard(state) }
  ```
- 建议验证方法：Compose Compiler Metrics（`-P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination`）确认 skippable 失效；Layout Inspector / `CompositionLocal` 重组高亮：下载时全部 Card 闪烁。修复方向（供 Li 参考）：updateProgress 拆独立 `StateFlow<Int>`（UpdateCard 单独 collect，或 VM 暴露 `updateProgressFlow`）；onNote 用 throttle（连接过程文案无需每条即时）；InstalledApp 的 icon 移出 equals 参与（或标记 @Immutable 的纯数据 + icon 单独通道）。

### BUG-035 shell 输出累积无总量上限，异常大输出可 OOM

- 级别：P2（防御缺口；常规调参命令输出小，但通道是通用 shell）
- 位置：`adb/AdbConnection.kt:74-79`（shell 的 ByteArrayOutputStream）
- 描述：round-1 BUG-013 修复引入字节累积（正确性需要），但**累积无上限**：`readPacket` 的 1MB 检查（:126）只约束单个 WRTE 包；一条命令的输出流（多包拼接）总量无界。`shell("$cmd; echo EXIT:$?")` 是通用通道——用户若在 profile/命令中执行 `cat /system/build.prop` 类大输出命令、或固件工具输出诊断转储（dumpsys 全量可达数 MB），全部驻留内存 + `toString("UTF-8")` 再翻倍（String 拷贝）。移动端数 MB 尚可承受、数十 MB 触发 GC 抖动、更大即 OOM。工具型 app 的 shell 通道应有输出预算。
- 证据：
  ```kotlin
  fun shell(command: String): Result<String> = runCatching {
      val bytes = java.io.ByteArrayOutputStream()   // 无上限
      shellStreaming(command) { chunk -> bytes.write(chunk) }
      bytes.toString("UTF-8")
  }
  ```
- 建议验证方法：构造 stub 流发送 >50MB 分包数据 → 观察 heap 增长；修复方向：累计超限（如 8MB）抛 IOException("output too large")，调用方 lastError 呈现——与 EXIT 探针缺失同等对待。

### BUG-036 AdbConnection.connect 失败不自清理 + 不可重入（资源语义不自洽）

- 级别：P2（设计债：当前唯一调用方纪律正确，无运行时泄漏；但类自身无防御）
- 位置：`adb/AdbConnection.kt:31-71`（connect 全体）；对照 `TuningExecutor.kt:84`（调用方补 close）
- 描述：两个关联缺口：
  1. **失败不自清理**：connect 内 `socket/input/output` 字段在 TCP 连接成功后即赋值（:38-40）；AUTH 阶段任何异常（rounds>8 / CRC mismatch / soTimeout / unexpected packet）经 runCatching 变成 `Result.failure` 返回，**socket 保持打开、字段残留**——清理完全依赖调用方记得 `conn.close()`（TuningExecutor.ensureConnected 失败分支确实做了）。类自身契约是"失败也要我自己收拾"才健壮；当前是"调用方必须知道要 close"，一旦出现第二个调用方（或重构）极易泄漏。
  2. **同实例二次 connect 覆盖旧 socket**：字段无状态检查，重复 connect 静默丢弃旧连接引用（不 close）——同样靠"用完即弃"的使用纪律兜底。
  这不是当前运行时 bug（调用方唯一且正确），但与 round-1 建立的"类自洽"标准（对照 AdbKeyStore 的自愈、shellStreaming 的锁内聚）不一致。
- 证据：
  ```kotlin
  }.getOrThrow()   // 异常路径直接抛出，无 finally 清理 socket/input/output
  return Result.success(info)
  // TuningExecutor.kt:84 —— 调用方兜底（唯一调用点）
  conn.close()
  ```
- 建议验证方法：代码走查即可确认无 finally；修复方向：connect 的 runCatching onFailure 分支内 `close()` 后再返回 failure（或 try/finally 包裹握手段）；二次 connect 前置 `check(socket == null)`。

### BUG-037 死代码群：TuningCommands 4 个零调用函数 + AdbProtocol 半个文件

- 级别：P2（维护负担与误导面；R8 已剥离运行时影响）
- 位置：`tuner/TuningCommands.kt:20,31-32,44,47-49`；`adb/AdbProtocol.kt:41-71`（decodeHeader/verifyCrc/decodeFull）
- 描述：grep 全工程（main + test）核实两组死代码：
  1. **TuningCommands.getRefreshRate() / getResolution() / getCpuGpuLevel() / setPassthroughQuality()**——round-1 时即无调用方（BUG-017 报告在案），round-2 方案决定保留 setPassthroughQuality（resetAll 语义完整），但**至今 5 轮无 UI 入口**，且 getRefreshRate/getResolution/getCpuGpuLevel 连"保留理由"都没有（statusCommand 已覆盖等价读取）。它们携带的 dumpsys 管道命令文档价值已被 README/PLAN 覆盖。
  2. **AdbProtocol.decodeHeader/verifyCrc/decodeFull**——round-1 BUG-012 修复后留作"公共 API"，但生产代码唯一解析路径是 AdbConnection.readPacket 自行解析；**三个函数连单测都没有**（24/26 用例无一触达）。verifyCrc 曾是恒 false 的陷阱历史（BUG-012），保留无测试的死 API 反而是回归温床。
- 证据：grep `getRefreshRate\(|getResolution\(|getCpuGpuLevel\(|setPassthroughQuality\(` → 仅定义处 4 行；grep `decodeHeader|verifyCrc|decodeFull` → 仅 AdbProtocol.kt 定义。
- 建议验证方法：上述 grep 复现。处置建议：TuningCommands 三 get 函数删除（setPassthroughQuality 与 resetAll 语义关联保留或一并删——Li 决策）；AdbProtocol 三函数要么删除要么补最小单测（encode→decodeHeader→verifyCrc 回环）——结合 round-1 遗留的"单测未补"项一并清账。

### BUG-038 依赖健康：datastore 零使用挂起 5 轮 + lifecycle-runtime-ktx 冗余

- 级别：P2（构建面/依赖面卫生；R8 已消除 APK 体积影响）
- 位置：`app/build.gradle.kts:62-65`
- 描述：两项：
  1. **datastore-preferences 1.1.1**：grep 全工程 `datastore|DataStore` 零命中（main/test 均无）——round-2 PLAN 风险段就写明"datastore 依赖当前无使用方，是否移除由 lead 决策，避免误伤"，该决策至今挂起。TunerPrefs 用 SharedPreferences（receiver/service 同步读的正确选择），datastore 永无启用路径。
  2. **lifecycle-runtime-ktx 2.8.3**：其核心 API（`lifecycleScope`/`repeatOnLifecycle`）全工程零使用——项目协程作用域全部是 `viewModelScope`（来自 lifecycle-viewmodel-ktx，由 lifecycle-viewmodel-compose 传递提供）。直接依赖可移除。
  两项不影响运行时（R8 剥离），代价是依赖解析/构建时间与"为什么在"的维护噪音——且 datastore 项已在 PLAN 里挂了 5 轮，应正式清账。
- 证据：grep 如上零命中；`viewModelScope` import 来自 `androidx.lifecycle`（传递依赖可用）。
- 建议验证方法：移除两行后三件套 + 26/26 单测全绿即证（我可在修复轮复验）。R8 规则方面：proguard-rules.pro 为空注释（AGP 自动 keep 已被 round-2 产物级验证覆盖），无收紧空间——本条无项。

---

## 优化建议（不阻塞，R5-OX）

| # | 位置 | 建议 |
|---|---|---|
| R5-O1 | AppPickerDialog.kt:92-94 | AppPickerRow 的 `toBitmap(64,64)` 在滚动时主线程执行（每行进入组合转换一次，自适应图标多层绘制）——数百 app 快速滚动可感知掉帧。建议 IconLoader 小 LruCache（packageName→ImageBitmap）或 `Dispatchers.Default` 异步转换 + placeholder |
| R5-O2 | SecureAutomation.kt:20-22 + TunerPrefs | 内存标记 wifiAdbEnabledByUs 与持久标记 wifiAdbByUs 双源并存（enable/disable 各同步写两处）——可将内存标记语义收窄为"本会话 connect 流程持有"并由 reconcile 独占持久标记，减少一处同步点 |
| R5-O3 | SecureAutomation/TuningExecutor | 连接入口不对称（ensureWifiAdbThenConnect 会开无线调试 vs ensureConnected 纯连接）是有意设计但只在注释里——建议在 TuningExecutor.ensureConnected KDoc 交叉引用，防止未来调用方误用 |
| R5-O4 | AdbConnection.kt:50-62 | AUTH tokenRounds 的 when(3+ 重发 pubkey) 分支语义（adbd 等待用户确认期间可能反复 TOKEN）补一行注释——round-1 验证时我推导过正确性，但代码不自解释 |
| R5-O5 | TunerViewModel | BUG-034 的最小修法：updateProgress 拆独立 StateFlow + UpdateCard 局部 collect（不动其余 UiState 结构，改动面 ~20 行）；onNote 加 200ms throttle |
| R5-O6 | AdbConnection.connect | BUG-036 的最小修法：onFailure 内 close 后返回 failure（保持 Result 签名不变，调用方无感） |

## 已核对无问题（本轮四条线的通过项）

- **LazyColumn key 稳定性**：主列表 profiles `key = packageName`（稳定唯一）；选择器 `key = packageName` ✓；8 个静态 item 无 key 需求 ✓。
- **图标内存无泄漏路径**：`remember(app.icon)` 随组合销毁释放；Dialog 关闭即整树销毁；无静态缓存持有 Bitmap ✓（性能顾虑见 R5-O1，非泄漏）。
- **选择器过滤 remember(apps, query)**：keys 正确，查询每字符只重算一次过滤 ✓。
- **AUTH 流程可读性主体**：轮换结构（SHA256→SHA1→pubkey）+ rounds 上限清晰，仅 R5-O4 的边界注释可补。
- **readPacket 防御**：单包 1MB 上限 + CRC + magic 三重校验在（总量缺口见 BUG-035）。
- **TuningExecutor/SecureAutomation 职责边界主体**：连接生命周期归 executor、系统设置写归 SecureAutomation、会话语义归 executor——分层成立，仅 R5-O3 的文档化建议。
- **UiState 字段无孤儿**：17 字段逐一核对全部有写入方+消费方（round-4 的 apps/appsLoading/updateChecking 已接入消费链）✓。
- **ConnectionCard 无旧逻辑残留**（round-4 三态按钮化后完整）；无重复工具函数（valuesMatch/mergeA11yList/checkHost/isTagSafe/versionGreaterThan 各司其职且有测试）。
- **R8 规则**：空规则文件 + AGP 自动 keep 已被 round-2/round-4 两次产物级验证背书，无可收紧项。
- **proguard/lint 配置**无回归。

## 修复优先级建议

1. **BUG-034 + R5-O5**（用户可感知的重组抖动，修法小）。
2. **BUG-035**（一行上限防御，与既有 1MB 检查同风格）。
3. **BUG-038**（两行删除 + 三件套复验即闭环，datastore 决策清账）。
4. **BUG-036 + BUG-037**（同一"类自洽/清债"主题，R5-O6 顺手；037 需 Li 对 setPassthroughQuality 去留做一次决策）。
