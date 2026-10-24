# QuestTuner Round 5 修复方案（PLAN-round5）

- 制定：Li（技术方案）
- 依据：docs/bugs/BUG-REPORT-round5.md（David，BUG-034~038 + R5-O1~O6）+ task-20 描述（含 lead 对 BUG-037 的决策）
- 基线：master @ fd7a777（round-4 已合入）；修复在 `fix/round-5` 分支进行
- 行号基于 fd7a777 核对；Wang 改动时如行号漂移请按代码片段定位
- 标注【改】给出改动后代码；【删】直接删除；【增】为新增行

## 总览

| 编号 | 级别 | 结论 | 涉及文件 |
|---|---|---|---|
| BUG-034 | P2 | 修（updateProgress 拆独立 StateFlow + UpdateCard 局部 collect + onNote 节流；R5-O5 一并落地） | TunerViewModel.kt、UiState、UpdateCard.kt |
| BUG-035 | P2 | 修（shell 累计 8MB 上限，IOException 语义） | AdbConnection.kt |
| BUG-036 | P2 | 修（connect 失败自清理 + 二次 connect 防御；R5-O6 一并落地） | AdbConnection.kt |
| BUG-037 | P2 | 修（按 lead 决策：TuningCommands 4 函数全删 + AdbProtocol 3 死 API 删除并确认无残留） | TuningCommands.kt、AdbProtocol.kt |
| BUG-038 | P2 | 修（删 datastore-preferences + lifecycle-runtime-ktx 两依赖） | app/build.gradle.kts |
| R5-O1~O4、O6 | 优化 | 2 修（O3/O4 注释）/ 2 延后（O1/O2）/ 2 并入（O5→034、O6→036） | 见 §优化建议 |

**建议实施顺序**（David 优先级）：① 034+R5-O5 → ② 035 → ③ 038 → ④ 036+R5-O6 → ⑤ 037（删除类改动放最后，避免行号漂移干扰前四项）。每步独立可编译。

---

## BUG-034（P2）单一 UiState 高频发射导致全树重组；不稳定类型放大跳过失效

- 涉及文件：`ui/TunerViewModel.kt`、`ui/UpdateCard.kt`
- 修复思路（R5-O5 的最小修法，不动 UiState 其余结构）：**把最高频的发射源拆出 UiState**——
  1. `updateProgress` 从 UiState 移到独立 `MutableStateFlow<Int>`：下载进度的 100 次发射只重组 UpdateCard 内部一个小 Composable，不再触发顶层 collectAsState；
  2. onNote 连接过程流加节流（首条立即 + 后续 200ms 合并）：连接文案无需每条即时，但首条（「已自动开启无线调试…」）必须立即反馈；
  3. `InstalledApp` 的 Drawable 不稳定类型问题**本轮不处理**（改动需动 data class + 选择器组合结构，收益场景仅选择器打开时——低频页面；留给 round-6 若做选择器虚拟化时一并），方案在 034 风险段记录。
- 具体改动点：

  **（a）TunerViewModel.kt【增】独立进度 Flow（类成员 :57 附近）**：
  ```kotlin
  /** BUG-034：下载进度独立通道——高频发射不再触发全树重组（UpdateCard 局部 collect） */
  private val _updateProgress = MutableStateFlow(-1)
  val updateProgress: StateFlow<Int> = _updateProgress
  ```
  UiState :47【删】`val updateProgress: Int = -1, ...` 字段（连同注释行；grep 确认其余引用点仅 UpdateCard/VM 内部）。

  **（b）TunerViewModel.kt :435-461 downloadAndInstall【改】**：所有 `_state.value = ...copy(updateProgress = ...)` 替换为 `_updateProgress.value = ...`：
  ```kotlin
  _state.value = _state.value.copy(updateStatus = "下载中…")
  _updateProgress.value = 0
  ...
  SelfUpdater.download(info.url, info.size, apk) { p ->
      _updateProgress.value = p        // 高频：独立 flow，不惊动 UiState
  }.onSuccess { f ->
      SelfUpdater.verifyApk(ctx, f).onSuccess {
          _state.value = _state.value.copy(updateStatus = "校验通过，等待系统确认…")
          _updateProgress.value = -1
      ...
  ```
  （其余 onFailure 分支同法替换；init/其他函数无 updateProgress 引用，无需改。）

  **（c）UpdateCard.kt【改】局部 collect**：
  ```kotlin
  // import 区【增】
  import androidx.compose.runtime.collectAsState

  @Composable
  fun UpdateCard(state: UiState, viewModel: TunerViewModel) {
      // BUG-034：进度局部订阅——下载期间只有本 Card 的进度条重组
      val progress by viewModel.updateProgress.collectAsState()
      ...
      if (progress >= 0) {
          LinearProgressIndicator(
              progress = { progress / 100f },
              modifier = Modifier.fillMaxWidth(),
          )
      }
      ...
  }
  ```

  **（d）onNote 节流（TunerViewModel.kt :135-137 connect 内）【改】**：
  ```kotlin
  connectJob = viewModelScope.launch {
      // BUG-034：连接过程文案节流（首条立即 + 后续 200ms 合并）——note 流不再逐条全树重组
      var lastNoteTime = 0L
      val ok = SecureAutomation.ensureWifiAdbThenConnect(getApplication()) { note ->
          val now = System.currentTimeMillis()
          if (now - lastNoteTime >= 200) {
              lastNoteTime = now
              _state.value = _state.value.copy(adbStatus = note)
          }
      }
      ...
  ```
  说明：ensureWifiAdbThenConnect 的 note 源头总共 ≤5 条（enable 四态 + 分类短路指引），实测频率本不高；节流是防御性收口（未来 note 增多时不退化），且「首条立即」保证用户操作反馈不延迟。无需引入 Sample Flow 操作符（依赖不变，手写两行更直白）。

- 风险与注意事项：
  - UiState 删字段是 breaking 改动：grep `updateProgress` 全工程（UiState 定义/VM 写入/UpdateCard 读取三处 + round-4 方案文档引用）确认改净；UpdateCard 的 `state.updateProgress` 引用必须全部换 `progress`。
  - 进度条视觉行为不变（0..100 显示、-1 隐藏）；「下载中…」文案仍在 UiState（低频，checkUpdate/终态各一次）。
  - InstalledApp/Drawable 不稳定：**记录不修**（低频页面 + 改动面大），若 round-6 做选择器优化时用「IconLoader 缓存 + InstalledApp 拆纯数据」一并处理。
  - 节流实现线程：onNote 回调在 IO 线程（ensureWifiAdbThenConnect 内），`_state.value =` 跨线程安全（既有事实），lastNoteTime 局部变量闭包内单协程访问，无竞态。
- 验证方法：
  1. Compose Compiler Metrics（可选）：UpdateCard 参数 state 稳定性恢复（进度已移出）。
  2. 实测：下载 2MB 更新包，Layout Inspector 重组高亮——仅 UpdateCard 进度区域闪，其余 7 个 Card 静止（修复前全闪）。
  3. 回归：进度条仍实时走动（0→100 平滑）；连接过程首条 note 立即出现（「已自动开启无线调试…」）。

---

## BUG-035（P2）shell 输出累积无总量上限，异常大输出可 OOM

- 涉及文件：`adb/AdbConnection.kt`
- 修复思路：与既有单包 1MB 检查（readPacket :126）同风格——累计超限抛 IOException，走 shell 的 runCatching → Result.failure → TuningExecutor.interpretShellResult 的既有失败链（lastError 呈现、IOException 清连接）。上限 8MB：常规调参/statusCommand 输出 <10KB，dumpsys 全量数 MB 仍可用，防御目标是异常大输出（数十 MB+）。
- 具体改动点：AdbConnection.kt:73-79【改】：
  ```kotlin
  /** Blocking shell; collect output until CLSE. Call from background thread. */
  fun shell(command: String): Result<String> = runCatching {
      // 字节累积 + 一次性 UTF-8 解码，避免多字节字符跨 WRTE 包截断
      val bytes = java.io.ByteArrayOutputStream()
      // BUG-035：累计上限（单包 1MB 检查不覆盖总量）——异常大输出按失败处理，防 OOM
      val limit = 8 * 1024 * 1024
      var total = 0L
      shellStreaming(command) { chunk ->
          total += chunk.size
          if (total > limit) throw IOException("shell output too large (>8MB): $command")
          bytes.write(chunk)
      }
      bytes.toString("UTF-8")
  }
  ```
  （IOException 从 onChunk 回调抛出 → shellStreaming 的 shellMutex.withLock 内传播 → runCatching 捕获 → Result.failure。连接清理：interpretShellResult 对 IOException 关闭连接——**这里有个语义细节**：输出超限不是连接死亡，是单命令输出异常；关连接偏保守但可接受（罕见路径，重连成本低，且与既有「IOException=连接不可信」的纪律一致，不为它开特例）。若 Wang 想更精细，可定义 `class OutputTooLargeException : IOException` 并在 interpretShellResult 跳过清连接——可选项，不强制。）

  companion object【增】（可选，将魔数提为常量）：`private const val MAX_SHELL_OUTPUT = 8 * 1024 * 1024`，上面引用之。
- 风险与注意事项：
  - 超限后 socket 中仍有未读数据 → 连接被 interpretShellResult 关闭（若采用可选的跳过清连接方案，残留数据会破坏后续命令的流解析——**因此默认方案「关连接」反而是唯一干净的处理**，方案定案：不引入特例异常，直接 IOException 清连接）。
  - 8MB 预算对 toString("UTF-8") 的翻倍拷贝：峰值 ~16MB+，移动端可承受；上限再小（如 4MB）也行，8MB 留足 dumpsys 余量。
- 验证方法：单测（构造 AdbConnection 不便——伪 socket 成本高；改为逻辑等价测试不可行时用实机/集成验证）：PC adb 推一个 >8MB 输出的命令场景（如 `cat` 大文件）经 profile 命令执行 → 失败提示「output too large」+ 连接自动断开重连可用；常规 statusCommand/getprop 全部正常。

---

## BUG-036（P2）AdbConnection.connect 失败不自清理 + 不可重入（资源语义不自洽）

- 涉及文件：`adb/AdbConnection.kt`（R5-O6 最小修法：onFailure 内 close，Result 签名不变，调用方无感）
- 具体改动点：AdbConnection.kt:31-71【改】：
  ```kotlin
  /** CNXN handshake; returns device info string on success. */
  fun connect(keyStore: AdbKeyStore): Result<String> {
      // BUG-036：同实例二次 connect 防御——旧连接未关闭即重连是调用方错误，快速失败优于静默泄漏
      check(socket == null) { "connect called on already-connected instance" }
      val info: String = runCatching {
          // ……原 :33-67 握手逻辑不变……
          deviceInfo
      }.getOrElse { e ->
          // BUG-036：失败自清理——socket/流字段残留是泄漏面，类自身契约「失败也自己收拾」
          close()
          throw e
      }
      return Result.success(info)
  }
  ```
  （getOrElse 内 close 后 rethrow → 外层返回 Result.failure；等价写法 `.onFailure { close() }` 后 `.getOrThrow()` 亦可，Wang 择一。注意：成功路径不 close。）
- 风险与注意事项：
  - `check(socket == null)`：当前唯一调用方 TuningExecutor 每次新建 AdbConnection 实例（:32 `val conn = AdbConnection(...)`），永不触发；防御针对未来第二调用方/重构。**release 构建下 check 抛异常**——这是期望（编程错误应炸响），非可恢复状态。
  - 失败清理与调用方兜底（TuningExecutor :84 `conn.close()`）双 close：close() 幂等（runCatching + 字段置 null），无害。
  - close() 内 runCatching 包裹 socket.close()：清理路径自身的异常不掩盖原握手异常。
- 验证方法：代码走查（失败路径 close 必达）；单测可选（伪 socket 成本高，走查 + 既有 26 用例回归即可）；实机回归：未授权连接超时路径（35s 失败）后 conn.close() 幂等（TuningExecutor 兜底仍执行），无行为变化。

---

## BUG-037（P2）死代码群：TuningCommands 4 个零调用函数 + AdbProtocol 半个文件

**lead 决策**：TuningCommands 4 个零调用函数（含 setPassthroughQuality）**全部删除**——无 UI 入口且 resetAll/statusCommand 已覆盖该属性的重置与读取，未来做透视质量开关时随 feature 重新引入；AdbProtocol 的 decodeHeader/verifyCrc/decodeFull **删除并确认无残留引用**。

- 涉及文件：`tuner/TuningCommands.kt`、`adb/AdbProtocol.kt`
- 具体改动点：

  **（a）TuningCommands.kt【删】4 个函数**：
  - :19-20 `getRefreshRate()`（连同 kdoc「读取当前刷新率」）
  - :31-32 `getResolution()`
  - :44 `getCpuGpuLevel()`
  - :46-49 `setPassthroughQuality()`（连同 kdoc「平滑透视…」）
  保留：setRefreshRate/setResolution/setCpuGpuLevel（AppProfile.toCommands 调用）、resetAll、statusCommand。删除后文件 ~40 行。

  **（b）AdbProtocol.kt【删】3 个函数**：
  - :41-53 `decodeHeader`
  - :55 `verifyCrc`
  - :57-69 `decodeFull`
  保留：常量群、Packet、encode、cmdName、crc32（AdbConnection.readPacket/encode 高频使用）。

  **（c）无残留确认（Wang 实施时必须执行的检查）**：
  ```
  grep -rn "getRefreshRate\|getResolution\|getCpuGpuLevel\|setPassthroughQuality" app/src/
  grep -rn "decodeHeader\|verifyCrc\|decodeFull" app/src/
  ```
  两条均应零命中（我已核对 fd7a777：7 处命中全部是定义处自身——TuningCommands 4 行 + AdbProtocol 3 行；测试目录无引用）。另检查 Packet 的 `crc` 字段：decodeHeader/decodeFull 删除后，crc 字段唯一写入方消失——readPacket（AdbConnection:116-131）构造 Packet 不传 crc（用默认 0），verifyCrc 删除后也无人读。**决策**：crc 字段一并【删】（`data class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)`）——round-1 BUG-012 为 verifyCrc 加的字段，宿主既删字段成死重量；readPacket 的构造调用（AdbConnection :131 `AdbProtocol.Packet(cmd, arg0, arg1, payload)`）恰好四个参数，无需改（round-1 加字段时带默认值，readPacket 从未传过 crc——需 Wang 删字段后编译确认）。

- 风险与注意事项：
  - 删除 setPassthroughQuality 后 resetAll 的 `highPrecisionPassthrough ""` 行（:56）**保留**——重置语义与 statusCommand 的读取（:68）构成完整闭环，这正是 lead 决策的依据，Wang 不要多删。
  - AdbKey.kt 顶部类注释提到的 android_pubkey 结构与 AdbProtocol 无关，不动。
  - 版本历史可追溯（git），死代码不留「以防万一」——这是本轮清债原则。
- 验证方法：两条 grep 零命中 + 三件套构建 + 26/26 单测全绿（无任何用例引用被删符号——已核对 IntentFold/ValuesMatch/UpdateSafety/VersionCompare/MergeA11y/LeaveSnapshot 六个测试文件）。

---

## BUG-038（P2）依赖健康：datastore 零使用挂起 5 轮 + lifecycle-runtime-ktx 冗余

- 涉及文件：`app/build.gradle.kts`
- 修复思路：两行删除，三件套 + 单测复验闭环。datastore 决策正式清账（round-2 PLAN 挂起至今）；lifecycle-runtime-ktx 的 lifecycleScope/repeatOnLifecycle 全工程零使用（已 grep 确认：`lifecycleScope|repeatOnLifecycle|datastore|DataStore` 四词零命中），viewModelScope 由 lifecycle-viewmodel-compose 传递提供。
- 具体改动点：build.gradle.kts:63【删】`implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")`；:65【删】`implementation("androidx.datastore:datastore-preferences:1.1.1")`。
- 风险与注意事项：
  - lifecycle-runtime-ktx 删除后若编译报 `lifecycleScope` 缺失——不会（零使用已核）；但**传递依赖收敛检查**：lifecycle-viewmodel-compose → lifecycle-viewmodel → lifecycle-runtime 链仍提供 ViewModel 基类所需 API，不受影响。
  - Compose 的 `LocalLifecycleOwner` 来自 lifecycle-runtime（非 ktx）经 compose-ui 传递——不受影响。
  - 若 David 复验时发现运行时 ClassNotFound（理论上不会），回滚方案 = 恢复两行（git revert 单 commit）。
- 验证方法：`gradle clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` 全绿；`gradle :app:dependencies` 确认 datastore 消失（David 复验清单已列）。

---

## 优化建议评估（R5-O1~O6）

| # | 结论 | 理由 / 改动点 |
|---|---|---|
| R5-O1 AppPickerRow 图标转换主线程掉帧 | **延后** | 掉帧场景 = 数百 app 快速滚动（选择器打开时）；选择器是低频页面、Dialog 生命周期短。LruCache 方案引入新状态管理（缓存失效/内存），异步转换引入 placeholder 闪烁——复杂度与收益不匹配。与 BUG-034 的 InstalledApp 不稳定问题同根（icon 通道），round-6 若做「选择器虚拟化 + 图标通道」一并处理 |
| R5-O2 双源标记收窄 | **延后** | wifiAdbEnabledByUs（内存）与 wifiAdbByUs（持久）双源是 round-4 BUG-032 的设计结果——内存标记服务「本会话回滚边界」、持久标记服务「跨进程对账」，语义不同非冗余；收窄需重推 reconcile 竞态矩阵（round-4 已推演收敛），当前同步点仅 enable/disable 两处且有回读保护——重推收益低。留 round-6 重构 SecureAutomation 时评估 |
| R5-O3 连接入口不对称文档化 | **本轮修** | TuningExecutor.ensureConnected KDoc【增】一行：「注意：本函数纯连接不触碰无线调试开关——自动开启属 SecureAutomation.ensureWifiAdbThenConnect（用户显式 connect 流程专用），服务/接收器侧自愈只可用本函数（BUG-033 语义）」。纯注释零风险 |
| R5-O4 AUTH tokenRounds 语义注释 | **本轮修** | AdbConnection.kt:50-62 AUTH 分支【增】注释：「tokenRounds≥3 时 adbd 仍在发 TOKEN = 未接受签名、等待用户在头显确认 pubkey 授权——每次重发同一 pubkey（幂等），rounds 总闸 8 防无限轮换」。round-1 David 推导过正确性，让代码自解释 |
| R5-O5 updateProgress 拆流 | **本轮修**（即 BUG-034 主体） | 见 034 全节 |
| R5-O6 connect 失败自清理最小修法 | **本轮修**（即 BUG-036 主体） | 见 036 全节 |

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-5`（master @ fd7a777 为基）。
2. **顺序**：① 034（VM/UiState/UpdateCard + onNote 节流）→ ② 035 → ③ 038 → ④ 036 → ⑤ 037 删除（最后做，grep 确认 + 编译）。R5-O3/O4 注释随手并入对应文件 commit。
3. **037 的删除纪律**：删完必须跑两条 grep（见该节 (c)）截图/记录零命中；Packet.crc 字段删除后全工程编译确认（readPacket 四参构造不传 crc，理论无感）。
4. **单测**：本轮无可单测的新逻辑（上限/自清理/删除/依赖），既有 26 用例保持绿即回归门。034 的节流逻辑若 Wang 想测：抽 `shouldEmitNote(lastTime, now): Boolean` 纯函数 + 2 用例（首条 true、200ms 内 false）——可选。
5. **不改动**：SecureAutomation/TuningExecutor 逻辑（仅 KDoc）、ForegroundProfileService、ProfileRepository、manifest、res。
6. **交付后自验清单**：
   1. 重组收敛（034）：下载更新时仅 UpdateCard 进度区变化（Layout Inspector 或肉眼：其余 Card 无闪烁）；进度 0→100 平滑；连接首条 note 立即。
   2. shell 上限（035）：常规 getprop/setprop/statusCommand 全部正常（无回归）；超限场景失败提示 + 重连可用。
   3. 依赖（038）：三件套全绿；dependencies 树无 datastore。
   4. 连接防御（036）：未授权超时路径回归正常（外部行为无变化）。
   5. 死代码（037）：grep 零命中；全量功能回归（连接/调参/应用/autoApply/更新）一轮。
