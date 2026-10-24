# QuestTuner Round 4 修复方案（PLAN-round4）

- 制定：Li（技术方案）
- 依据：docs/bugs/BUG-REPORT-round4.md（David，BUG-029~033 + 优化建议 R4-O1~O8）+ task-16 描述（含 lead 对 BUG-033 的产品决策）
- 基线：master @ 02f6bc1（round-3/3b 已合入）；修复在 `fix/round-4` 分支进行
- 行号基于 02f6bc1 核对；Wang 改动时如行号漂移请按代码片段定位
- 标注【改】给出改动后代码；【删】直接删除；【增】为新增行/文件
- 沿用假设台账风格（接续 A1-A12，本轮新增 A13）

## 总览

| 编号 | 级别 | 结论 | 涉及文件 |
|---|---|---|---|
| BUG-029 | P1 | 修（onStart 重探测 + 向导步骤切换重探测，双兜底） | MainActivity.kt、TunerViewModel.kt、SetupWizard.kt |
| BUG-030 | P2 | 修（refreshCapabilities 内对账补启用 + autoApply 门禁改 a11yRunning + 副文案） | TunerViewModel.kt、AutomationCard.kt、SetupWizard.kt |
| BUG-031 | P2 | 修（手改包名即失效 pickedLabel + 保存时守卫） | TunerApp.kt |
| BUG-032 | P2 | 修（wifiAdbByUs 持久化 + 启动对账，只回滚自己标记的） | TunerPrefs.kt、SecureAutomation.kt、TunerViewModel.kt |
| BUG-033 | P2 | 修（按 lead 决策：中文单语 + TalkBack 语义绑定 + resConfigs("zh","en")） | TunerApp.kt、AutomationCard.kt、SetupWizard.kt、app/build.gradle.kts |
| R4-O1~O8 | 优化 | 6 修（O1/O2/O3/O4/O5/O8，其中 O3/O4/O5 并入 bug 项）/ 1 延后（O7）/ 1 并入（O6→031） | 见 §优化建议 |

**建议实施顺序**：① 029（refreshCapabilities 基础设施 + MainActivity + 向导切换 + R4-O3 首帧修复，全在 VM/Activity/Wizard 三文件）→ ② 030（对账逻辑并入 refreshCapabilities + 门禁与文案 + R4-O5）→ ③ 032（TunerPrefs + SecureAutomation + VM init 对账）→ ④ 031（+R4-O6）→ ⑤ 033（语义绑定 + resConfigs）→ ⑥ R4-O1/O2/O8 小项。每步独立可编译。

---

## 假设台账（round-4 新增）

| # | 假设 | 运行时探测方法 | 失败时行为 |
|---|---|---|---|
| A13 | 头显摘下/戴上（接近传感器息屏/亮屏）会触发 Activity onStop/onStart——BUG-029 用 onStart 重探测覆盖「去 PC 授权 → 回头显」旅程的前提 | MainActivity onStart 调用即日志可证（加一行 Log 或走查） | 个别固件 app 常驻前台不触发 → 向导「下一步」步骤切换时重探测（确定性用户动作兜底，见 029 (c)）+ AutomationCard 的复制命令按钮点击后亦重探测（第三兜底，见 (d) 可选项） |
| （引用）A2 | adb_wifi_enabled 写入被固件接受（round-2 已登记）——BUG-032 启动对账的 disable 同样走 putInt+回读 | disableWifiAdb 既有回读 | WriteRejected → 持久标记不清（下次启动重试回滚），见 032 风险段 |
| （引用）A5 | adb_wifi_enabled=1 跨进程死亡/重启保持——BUG-032 的「残留监听」正是 A5 的另一面 | Settings.Global.getInt 回读 | 标记与实态都由回读确认，不新增假设 |

---

## BUG-029（P1）授权能力快照永不刷新：PC 授权后首次旅程断链

- 涉及文件：`MainActivity.kt`、`ui/TunerViewModel.kt`、`ui/SetupWizard.kt`
- 修复思路：把「能力探测」从 init 一次性快照重构为可重复调用的 `refreshCapabilities()`，三个触发点形成双兜底：
  1. **MainActivity.onStart**（主路径）：头显摘下（息屏）→ onStop、戴回 → onStart——用户去 PC 执行 pm grant 再回来的旅程天然覆盖（A13）；
  2. **向导第一步 → 第二步切换**（确定性兜底）：用户按指引完成授权后点「下一步」，此刻重探测——不依赖任何生命周期事件；
  3. **init 保留**（进程首起）：refreshCapabilities 直接在 init 调用，取代原快照字段赋值。
- 具体改动点：

  **（a）TunerViewModel.kt【增】refreshCapabilities（放 init 块之后）**：
  ```kotlin
  /**
   * BUG-029：能力快照重探测（onStart / 向导步骤切换 / init 三处触发）。
   * BUG-030 对账：autoA11y 开关 ON 且已授权但服务未运行 → 自动补执行 enable（一次，结果如实回填）。
   */
  fun refreshCapabilities() {
      viewModelScope.launch(Dispatchers.IO) {
          val app = getApplication<Application>()
          val grant = SecureAutomation.hasWriteSecureSettings(app)
          var running = SecureAutomation.isA11yRunning(app)
          // BUG-030 对账：持久 ON + 授权在 + 服务未运行 → 补启用（对照 autoWifiAdb 每次 connect 活读的自然对账）
          if (grant && prefs.autoA11y && !running) {
              if (SecureAutomation.enableA11yService(app) is SecureAutomation.CapProbe.Ok) {
                  running = SecureAutomation.waitA11yRunning(app)
              }
          }
          _state.value = _state.value.copy(
              hasSecureGrant = grant,
              a11yRunning = running,
              privateDnsMode = PrivateDns.currentMode(app),
          )
      }
  }
  ```
  init 块（:61-78）【改】：快照协程瘦身——prefs 镜像字段保留在 init（开关值变化只经 setXxx，无需重读），能力字段移交 refreshCapabilities：
  ```kotlin
  init {
      viewModelScope.launch {
          val ps = withContext(Dispatchers.IO) { repo.profiles() }
          _state.value = _state.value.copy(profiles = ps)
      }
      refreshCapabilities()   //【增】取代原 :66/:71/:76 三处一次性快照
      viewModelScope.launch { SelfUpdater.installResult.collect { ... } }   // 原样保留
      viewModelScope.launch { TuningExecutor.connectionState.collect { ... } }  // 原样保留
  }
  ```
  （原 init 中 `hasSecureGrant = ...`、`a11yRunning = ...`、`privateDnsMode = ...` 三行【删】，其余 prefs 镜像行保留。）

  **（b）MainActivity.kt【改】**：
  ```kotlin
  override fun onStart() {
      super.onStart()
      // BUG-029：回到前台重探测能力快照（PC 授权后返回、戴回头显等旅程）
      viewModel.refreshCapabilities()
  }
  ```

  **（c）SetupWizard.kt:63-65【改】步骤切换触发重探测**：
  ```kotlin
  confirmButton = {
      if (step == 1) {
          TextButton(onClick = {
              // BUG-029：用户按指引完成授权后进入第二步——此刻重探测（确定性兜底，不依赖生命周期）
              viewModel.refreshCapabilities()
              step = 2
          }) { Text("下一步") }
      } else { ... }
  }
  ```

  **（d）R4-O3 首帧闪烁顺带修复（同文件）**：TunerViewModel.kt:54【改】：
  ```kotlin
  // R4-O3：wizardVisible 构造时同步读 prefs（SP 首读开销可忽略），消除「主界面闪现再弹向导」
  private val _state = MutableStateFlow(UiState(wizardVisible = !prefs.firstRunDone))
  ```
  并【删】init 快照中的 `wizardVisible = !prefs.firstRunDone` 行（:72，避免重复）。

- 风险与注意事项：
  - refreshCapabilities 的对账分支有启用副作用（enableA11yService 写系统设置）——只在 `prefs.autoA11y`（用户明确开过）且未运行时触发，不违背开关隔离；每次 onStart 都查一次 `isA11yRunning`（一次 binder 调用，开销可忽略）。
  - onStart 与 setAutoA11y 的 enable 协程并发：enableA11yService 幂等（已含则跳过写入，SecureAutomation:88-91 既有序列化检查），最坏两次并发读改写 enabled_accessibility_services——概率极低且结果收敛（追加式幂等），可接受；若 Wang 想彻底规避可在 refreshCapabilities 与 setAutoA11y 间加 VM 级 Mutex（可选，不强制）。
  - onStart 频繁触发（分屏/切换）：全部是只读探测 + 至多一次幂等 enable，无副作用累积。
- 验证方法：
  1. 旅程主验证（David 建议法）：装 app → 向导复制命令 → PC 执行 pm grant → **点「下一步」**（兜底路径）→ 第二步授权提示消失、开关可用；再验证摘头显→戴回（onStart 路径） AutomationCard 从置灰变可用。
  2. 对照证据：`adb shell dumpsys package com.example.questtuner | grep WRITE_SECURE` 显示已授予，与 UI 一致。
  3. R4-O3：冷启首帧直接是向导，无主界面闪现。

---

## BUG-030（P2）autoA11y/autoApply 状态与系统实态无对账，静默失效

- 涉及文件：`ui/TunerViewModel.kt`（对账已并入 refreshCapabilities，见 029(a)）、`ui/AutomationCard.kt`、`ui/SetupWizard.kt`
- 修复思路：
  1. **autoA11y 自愈对账**：并入 refreshCapabilities（029 已含）——`grant && prefs.autoA11y && !running` 时自动补执行 enable，成功后行为级确认回填。与 autoWifiAdb「每次 connect 活读」的对账粒度对齐。
  2. **autoApply 门禁修正**：真实依赖是「服务运行中」，门禁从 `state.autoA11y || grant` 改为 `state.a11yRunning`；副文案按 a11yRunning 实时区分（R4-O4 一并落地）。
- 具体改动点：

  **（a）AutomationCard.kt:64-68【改】autoApply 开关**：
  ```kotlin
  SwitchRow(
      "按前台应用自动应用 Profile",
      // R4-O4：副文案按实态区分，消除静默失效
      if (state.a11yRunning) "仅本地监听窗口切换，不上传任何数据；需已连接——断开后自动应用暂停，重新连接后恢复"
      else "无障碍服务未运行，自动应用不会触发——请先开启上方「自动启用无障碍服务」",
      state.autoApply, state.a11yRunning,   //【改】门禁 = 真实依赖（服务运行），不再是 autoA11y||grant
  ) { viewModel.setAutoApply(it) }
  ```
  语义说明：用户必须先开「自动启用无障碍服务」且服务确实 running，autoApply 才可开——依赖顺序在 UI 上显式化（grant→autoA11y→a11yRunning→autoApply 链条自上而下）。autoApply 已 ON 但服务后来被系统关掉：开关保持 ON（checked 态）但置灰，副文案提示——如实表达「开着但不会生效」。

  **（b）SetupWizard.kt:55-58【改】向导开关门槛（R4-O5 一并落地，与 AutomationCard 语义统一）**：
  ```kotlin
  WizardSwitch("连接时自动开启无线调试", state.autoWifiAdb, state.hasSecureGrant) { viewModel.setAutoWifiAdb(it) }
  WizardSwitch("自动启用无障碍服务", state.autoA11y, state.hasSecureGrant) { viewModel.setAutoA11y(it) }
  WizardSwitch("按前台应用自动应用 Profile", state.autoApply, state.a11yRunning) { viewModel.setAutoApply(it) }
  WizardSwitch("开机恢复默认参数", state.restoreOnBoot, state.hasSecureGrant) { viewModel.setRestoreOnBoot(it) }
  ```
  （WizardSwitch 签名【增】enabled 参数，见 BUG-033 (b) 的语义改造——两项同函数一次改。）

- 风险与注意事项：
  - 门禁改 a11yRunning 后，「grant=true 但从未启无障碍」的用户不能再提前勾选 autoApply——这是修正而非收紧损失（原可勾选态是静默失效的陷阱）；向导第二步顶部的授权提示文案（:49-52）保持。
  - a11yRunning 的刷新时机：onStart/向导切换/init（029 三触点）+ setAutoA11y 即时回填——用户在系统设置手动关掉服务后回 app，onStart 重探测即捕捉（副文案如实变化）。
- 验证方法：
  1. 未授权时开 autoA11y → PC 授权 → 回 app（onStart）→ 开关 ON 且服务被自动补启用（系统设置可见）+ 副文案「服务运行中」。
  2. grant=true、无障碍从未开：autoApply 开关置灰 + 副文案「无障碍服务未运行…」；开启 autoA11y 且 running 后 autoApply 恢复可点。
  3. autoApply ON 时手动关系统无障碍 → 回 app → 开关置灰、副文案切换、（下次前台切换）无命令执行。

---

## BUG-031（P2）NewProfileCard pickedLabel 陈旧：label 与 pkg 不匹配

- 涉及文件：`ui/TunerApp.kt`
- 修复思路（联动策略，两道防线）：
  1. **入口失效**（主修复）：跟踪 `pickedPkg`（选择器写入的包名）；包名输入框 onValueChange 时，当前值 ≠ pickedPkg 即清空 pickedLabel——手改任何字符都使陈旧 label 失效，label 的有效期严格绑定「包名仍等于选择器所选」。
  2. **保存守卫**（防御，R4-O6 并入）：保存时最终校验一次（pkg == pickedPkg 才采用 pickedLabel），覆盖入口清理的漏网（理论上无，防御性一行）。
  - 备选（落选）：只在保存时校验——输入过程中 UI 无反馈，用户看不到 label 已不匹配；入口失效让「选择应用」按钮状态也可联动（见 (c) 可选）。
- 具体改动点：

  **（a）TunerApp.kt:159【改】状态区**：
  ```kotlin
  var pickedLabel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  //【增】选择器所选包名：pickedLabel 的有效期锚点（BUG-031）
  var pickedPkg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  ```

  **（b）TunerApp.kt:167-171（onSelect）与 :177-182（输入框）【改】**：
  ```kotlin
  onSelect = { app ->
      pkg = app.packageName
      pickedPkg = app.packageName      //【增】记录锚点
      pickedLabel = app.label
      showPicker = false
  },
  ...
  androidx.compose.material3.OutlinedTextField(
      value = pkg,
      onValueChange = {
          pkg = it
          // BUG-031：手改包名 → 与选择器所选不一致 → 陈旧 label 立即失效
          if (it != pickedPkg) pickedLabel = null
      },
      placeholder = { Text("com.example.app（留空=全局）") },
      singleLine = true,
  )
  ```

  **（c）TunerApp.kt:204-220 保存按钮 onClick【改】（保存守卫，R4-O6）**：
  ```kotlin
  onClick = {
      val w = (1680 * scale).toInt().coerceAtMost(3072)
      val h = (1760 * scale).toInt().coerceAtMost(3200)
      // BUG-031/R4-O6：label 只在包名仍等于选择器所选时采用（防御性终检）
      val label = (if (pkg == pickedPkg) pickedLabel else null) ?: pkg.ifBlank { "全局" }
      viewModel.saveProfile(
          AppProfile(
              packageName = pkg.ifBlank { "global" },
              label = label,
              ...
          )
      )
  }
  ```

- 风险与注意事项：
  - 选择后未改包名：pickedPkg == pkg，label 保留 ✅；手改后改回原包名：onValueChange 时 it == pickedPkg 不成立（改回过程中的中间值已清 label）→ label 丢失，save 回退用包名做 label——可接受（用户手动编辑过，字段语义以用户输入为准）。
  - 键盘输入法重组等非用户改动不触发 onValueChange（只有真实编辑触发），无误清。
- 验证方法：选择应用 A → 手改包名为 com.b → 保存 → ProfileRow 显示 com.b（不是 A 的名字）；选择 A 不改 → 保存 → 显示 A。autoApply 语义一致（label 与 pkg 对应）。

---

## BUG-032（P2）wifiAdbEnabledByUs 内存标记：进程被杀后无线调试回滚永不发生

- 涉及文件：`tuner/TunerPrefs.kt`、`tuner/SecureAutomation.kt`、`ui/TunerViewModel.kt`
- 修复思路（lead 已定安全方向：**只回滚自己开的标记，保守方向**）：
  1. **持久化**：TunerPrefs 增 `wifiAdbByUs` 键；enableWifiAdb 成功（回读确认）时写 true，disableWifiAdb 成功（回读确认 off）时写 false——持久标记与内存标记在成功路径上同步移动，单一事实源是系统设置本身，标记只是「归属声明」。
  2. **启动对账**：VM init（refreshCapabilities 旁）调 `reconcileWifiAdbOnStartup`：持久标记 true 且本会话未开启（内存标记 false）→ 上次会话残留 → 回滚（disable + 清标记）。本会话已开启则跳过（正常生命周期管理中，disconnect 负责回滚）。
  3. **保守边界**：只在持久标记为我们所置时回滚——用户手动开的无线调试（标记 false）永不被启动对账触碰；disable 失败（WriteRejected/无权限）时持久标记不清，下次启动重试回滚。
- 具体改动点：

  **（a）TunerPrefs.kt【增】**（属性区 :33 后 + 常量区 :43 后）：
  ```kotlin
  /** BUG-032：「无线调试由本应用开启」持久标记（进程被杀后启动对账用） */
  var wifiAdbByUs: Boolean
      get() = sp.getBoolean(K_WIFI_ADB_BY_US, false)
      set(v) = sp.edit().putBoolean(K_WIFI_ADB_BY_US, v).apply()
  ...
  private const val K_WIFI_ADB_BY_US = "wifi_adb_by_us"
  ```

  **（b）SecureAutomation.kt【改】enable/disable 同步持久标记**：
  - enableWifiAdb（:43-55）成功分支：
    ```kotlin
    if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
        wifiAdbEnabledByUs = true
        TunerPrefs.get(context).wifiAdbByUs = true    //【增】持久归属声明
        CapProbe.Ok
    } else ...
    ```
  - disableWifiAdb（:57-66）：
    ```kotlin
    Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
    wifiAdbEnabledByUs = false
    if (Settings.Global.getInt(cr, "adb_wifi_enabled", -1) == 0) {
        TunerPrefs.get(context).wifiAdbByUs = false   //【增】回滚确认成功才清标记
        CapProbe.Ok
    } else CapProbe.WriteRejected                      // 回读仍 1 → 标记保留，下次重试
    ```

  **（c）SecureAutomation.kt【增】启动对账函数**（disableWifiAdbIfWeEnabled :68-74 之后）：
  ```kotlin
  /**
   * BUG-032 启动对账：上次会话「由我们开启的无线调试」可能因进程被杀未回滚。
   * 保守方向：仅当持久标记为我们所置时回滚；本会话已开启（内存标记 true，
   * 正常生命周期管理中）则跳过。disable 失败标记保留，下次启动重试。
   */
  fun reconcileWifiAdbOnStartup(context: Context) {
      if (wifiAdbEnabledByUs) return   // 本会话已开（connect 流程先于对账跑完的竞态）——disconnect 负责
      val prefs = TunerPrefs.get(context)
      if (prefs.wifiAdbByUs) {
          disableWifiAdb(context)      // 成功则内部已清持久标记；失败则保留待重试
      }
  }
  ```
  竞态推演：对账协程与用户 connect 并发——若 connect 的 enable 先成功，内存标记 true → 对账跳过 ✅；若对账先跑，disable 掉残留后 connect 再 enable ✅——两种顺序都收敛正确。

  **（d）TunerViewModel.kt init【增】**（refreshCapabilities() 调用旁）：
  ```kotlin
  viewModelScope.launch(Dispatchers.IO) {
      // BUG-032：启动对账——上次会话残留的「我们开的无线调试」回滚
      SecureAutomation.reconcileWifiAdbOnStartup(getApplication())
  }
  ```

- 风险与注意事项：
  - **卸载边界**（记录不处理）：卸载重装会清 app 数据（持久标记消失）但 adb_wifi_enabled 是全局设置可能残留——此时无标记可对账、且新装 app 无权限关闭。属平台边界，超出可控范围；重装后用户 connect→disconnect 一轮即恢复（标记重新建立）。
  - BootReceiver 不加对账：开机恢复流程本身不 enable 无线调试（round-3 O2 已核对），且 receiver 只在 restoreOnBoot 开启时运行——不是通用启动钩子；对账收口在 app 启动（VM init）是明确、可测试的单一入口。设备重启但用户不开 app 的残留窗口：接受（用户下次打开即回滚），开关文案「断开时自动恢复」的主语义不变。
  - 持久标记与内存标记短暂不一致（enable 成功、进程在写 prefs 前被杀）：apply() 异步落盘的窗口极小；最坏后果是多残留一轮（下次对账仍会处理，因为 prefs 未写成 → 视为非我们所开 → 不回滚——**这是保守方向的正确失败**：宁可漏回滚（用户手动关一次）也不误关用户手动开的）。反过来 enable 写 prefs 成功但内存赋值前被杀：下次对账标记 true → 回滚 ✅。
- 验证方法：
  1. David 建议法：授权+autoWifiAdb 开 → 连接（无线调试已开）→ `adb shell am kill com.example.questtuner` → 重开 app → 无线调试被自动关闭（系统设置确认），无需 connect/disconnect 一轮。
  2. 保守边界：用户**手动**开无线调试（app 从未自动开过）→ am kill → 重开 app → 无线调试**保持开**（未被误关）。
  3. 正常路径回归：connect（自动开）→ disconnect → 无线调试关（原行为不变，持久标记同步清）。

---

## BUG-033（P2）i18n 与无障碍语义基础缺失（按 lead 决策执行）

**lead 产品决策**：应用 UI 保持中文单语（不做全量资源化，README/strings 不变），但 ① TalkBack 语义绑定必须修（中文文案即可）；② build.gradle.kts 加 `resConfigs("zh","en")` 裁剪依赖库多语言资源。

### 涉及文件

`ui/TunerApp.kt`、`ui/AutomationCard.kt`、`ui/SetupWizard.kt`、`app/build.gradle.kts`

### 具体改动点

**（a）Slider 语义绑定（TunerApp.kt:199/201/203 三个 Slider）【改】**：
```kotlin
// import 区【增】：
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// :199 纹理缩放
Slider(
    value = scale, onValueChange = { scale = it }, valueRange = 0.5f..2.0f,
    modifier = Modifier.semantics { contentDescription = "纹理缩放" },   //【增】
)
// :201 CPU
Slider(
    value = cpu.toFloat(), onValueChange = { cpu = it.toInt() }, valueRange = 0f..4f, steps = 3,
    modifier = Modifier.semantics { contentDescription = "CPU 性能等级" },   //【增】
)
// :203 GPU 同 CPU，文案 "GPU 性能等级"
```
（Slider 自带 stateDescription 读出当前值/范围，绑定 contentDescription 后 TalkBack 读「CPU 性能等级，滑块，2/4」类完整语义。）

**（b）SwitchRow / WizardSwitch 语义绑定（可点行 + Role.Switch，规范做法）**：

AutomationCard.kt:81-100 SwitchRow【改】：
```kotlin
// import 区【增】：
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

@Composable
private fun SwitchRow(
    title: String, note: String, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            // BUG-033：整行可点 + Switch 语义——TalkBack 聚焦读出「标题、副文案、开关、状态」，触达面积更大
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
        // onCheckedChange=null：点击语义由整行承载，避免 TalkBack 双重播报
        Switch(checked = checked, onCheckedChange = null)
    }
}
```
SetupWizard.kt:72-80 WizardSwitch【改】（同时落 R4-O5 的 enabled 参数）：
```kotlin
@Composable
private fun WizardSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
```
（SwitchRow 原有的 `Switch(onCheckedChange = if (enabled) onChange else null)` 模式【删】——toggleable 行接管 enabled 门禁与点击。）

**（c）build.gradle.kts defaultConfig（:11-17）【改】resConfigs**：
```kotlin
defaultConfig {
    applicationId = "com.example.questtuner"
    minSdk = 32
    targetSdk = 32
    versionCode = 2
    versionName = "0.2.0"
    // BUG-033（lead 决策）：中文单语产品 + 依赖库资源裁剪（保留 zh 与库默认 en，剥离其余 locale）
    resourceConfigurations.addAll(listOf("zh", "en"))
}
```

**（d）可选增强（Wang 余量，不强制）**：NewProfileCard 刷新率 FilterChip（:190-196）加 `modifier = Modifier.semantics { contentDescription = "刷新率 $c Hz" }`；PrivateDnsSection 输入框加 `label = { Text("DNS 主机名") }`（label 比 placeholder 对 TalkBack 更友好）。

### 风险与注意事项

- toggleable 行为变化：开关整行可点（原来只 Switch 可点）——UI 交互增强非回归；注意 AutomationCard 的 SwitchRow 调用方签名未变，无需适配。
- resConfigs 裁剪后系统切英文：app 自身文案仍中文（硬编码，产品决策接受），依赖库内部文案（若展示）回落 en/default——预期行为。
- `resourceConfigurations.addAll` 是 AGP 8 Kotlin DSL 写法；若构建报 DSL 不存在，用 Groovy 风格 `resConfigs("zh", "en")`（AGP 版本兼容性，Wang 按构建报错择一）。

### 验证方法

1. TalkBack 实测：聚焦纹理/CPU/GPU 滑块 → 读出「纹理缩放…」而非裸「滑块」；聚焦任一开关行 → 读出「标题+状态+开关」并可双击切换。
2. `assembleRelease` 后 `aapt2 dump badging` 或 apkanalyzer 对比资源体积（locale 裁剪生效、APK 变小）。
3. 回归：开关点击行为（整行可点）、enabled 门禁（030 的 a11yRunning 门禁在 toggleable 上生效）。

---

## 优化建议评估（R4-O1~O8）

| # | 结论 | 理由 / 改动点 |
|---|---|---|
| R4-O1 update/ 目录陈旧文件清理 | **本轮修** | SelfUpdater【增】`cleanStaleApks(context, keepTag)`：列出 update/ 目录删除非 `questtuner-$keepTag.apk` 文件（runCatching 包裹）。downloadAndInstall（VM :404 下载前）调用 `SelfUpdater.cleanStaleApks(ctx, info.tag)`。跨版本残留 APK 不再累积 |
| R4-O2 cancelConnect 收尾兜底 | **本轮修** | cancelConnect（VM :137-144）【增】IO 协程内：`TuningExecutor.markSessionInactive(); TuningExecutor.disconnect()`——取消后即使阻塞读晚到完成连接，也被关闭且会话不激活（否则出现「连接活但 sessionActive=false」中间态，服务还会自愈重连违背取消意图） |
| R4-O3 wizardVisible 首帧闪烁 | **本轮修**（并入 BUG-029） | 见 029 (d)：VM 构造时同步读 prefs 初始化 UiState |
| R4-O4 autoApply 副文案实态化 | **本轮修**（并入 BUG-030） | 见 030 (a)：按 a11yRunning 区分两段文案 |
| R4-O5 WizardSwitch 门槛统一 | **本轮修**（并入 BUG-030/033） | 见 030 (b) + 033 (b)：enabled 参数 + toggleable |
| R4-O6 保存时 label/pkg 校验 | **本轮修**（并入 BUG-031） | 见 031 (c) 保存守卫 |
| R4-O7 adbStatus 覆盖链去重 | **延后** | 纯观感问题（两条近似「连接已断开」先后覆盖，无错误信息）；修法要么引入状态合并管道要么去重比较器，触碰所有状态发布点——round-4 改动面已足够大，为低收益引入回归风险不值。round-5 若做 UI 状态层重构时顺带 |
| R4-O8 AppPicker 无结果占位 | **本轮修** | AppPickerDialog.kt:71-77 的 else 分支【改】：`if (filtered.isEmpty()) { Text("无匹配应用", style = bodySmall) } else { LazyColumn ... }`——搜索无结果不再空白 |

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-4`（master @ 02f6bc1 为基）。
2. **顺序**：① 029（VM/Activity/Wizard + R4-O3）→ ② 030（AutomationCard/Wizard 门禁文案）→ ③ 032（TunerPrefs/SecureAutomation/VM）→ ④ 031（TunerApp）→ ⑤ 033（语义 + resConfigs，注意 030 的 WizardSwitch enabled 与 033 的 toggleable 是同函数一次改）→ ⑥ R4-O1/O2/O8。
3. **030 与 033 的交叠**：WizardSwitch 的 enabled 参数（030/R4-O5）与 toggleable 改造（033）必须同一 commit 完成——分开会编译不过（签名不匹配）。
4. **单测**：本轮逻辑集中在生命周期/系统状态交互，纯函数少——`cleanStaleApks`（临时目录 + 两个 tag 文件断言只留 keep）可测；对账/门禁逻辑建议走查 + 实机清单。既有 24 用例保持绿。
5. **不改动**：adb/ 包、TuningExecutor 连接核心、ForegroundProfileService（round-3 成果，本轮四个 bug 均不涉及服务内部）；SelfUpdater 安全链（只加 cleanStaleApks 一个纯文件操作函数）。
6. **交付后自验清单**：
   1. 授权旅程（029）：向导→PC 授权→「下一步」即刷新；摘戴头显 onStart 刷新；AutomationCard/PrivateDns 区解锁
   2. 对账自愈（030）：未授权开 autoA11y→授权→回 app 自动补启用；autoApply 门禁随 a11yRunning 变化 + 副文案如实
   3. label 一致（031）：选 A 改包名保存 → label==pkg；不改 → label==A
   4. 残留回滚（032）：am kill → 重开 app 无线调试自动关；手动开的不会被误关；正常 disconnect 回归
   5. TalkBack（033）：滑块/开关读出中文语义；开关整行可点；en 环境库资源裁剪后 APK 变小
   6. R4 项：换版本更新后 update/ 无旧 APK；取消连接无「晚到连接」残留；首帧无闪烁；搜索无结果有占位
