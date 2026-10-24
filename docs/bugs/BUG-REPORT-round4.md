# QuestTuner Round 4 Bug 报告（用户旅程与状态一致性审查）

- 审查人：David（QA）
- 日期：2026-10-24
- 基线：master @ 02f6bc1（round-3/3b 已合入，工作树与分支一致已核对）
- 本轮角度：用户旅程端到端 / TunerPrefs 三处读取一致性 / 进程被杀状态恢复 / 资源与国际化。**不重复 round-1~3 已核对项**（已知不重报：O4 finally 已修、scheme 已修、重定向逐跳为记录在案可选项、RISK-1 家族文案语义、round-2 向导遮挡文案）。
- 编号自 BUG-029 起

## 汇总

| 编号 | 级别 | 位置 | 摘要 |
|---|---|---|---|
| BUG-029 | **P1** | TunerViewModel.kt:66 / SetupWizard.kt:49 / AutomationCard.kt:35 | hasSecureGrant 等能力快照仅在 VM init 采集一次，PC 授权后返回 app 永远显示"未授权"直到杀进程——首次授权旅程断链 |
| BUG-030 | P2 | TunerViewModel.kt:71,167-210 / AutomationCard.kt:67 | autoA11y 开关持久 ON 但服务未运行时无对账自愈；autoApply 的 UI 门禁条件（autoA11y‖grant）与真实依赖（服务运行）不符——静默失效 |
| BUG-031 | P2 | TunerApp.kt:162-166,205 | 选择应用后手动改包名，pickedLabel 不清理——保存的 profile label 与 pkg 不匹配 |
| BUG-032 | P2 | SecureAutomation.kt:20-22,69-74 | wifiAdbEnabledByUs 内存标记：自动开启无线调试后进程被杀（Quest 后台杀常见），回滚永不发生且 UI 无触发路径——"断开时自动恢复"承诺失效 |
| BUG-033 | P2 | 全部 UI 文件 / strings.xml | i18n 基础缺失：全部文案硬编码中文（strings.xml 仅 2 条 a11y 文案）；Slider/开关行无语义绑定（TalkBack 不可用标签）|

优化建议（8 项）见专门章节。

---

## P1

### BUG-029 授权能力快照永不刷新：PC 授权后首次旅程断链

- 级别：P1（headline 功能首次使用路径死路，杀进程是唯一解法）
- 位置：`ui/TunerViewModel.kt:66`（唯一采集点，VM init）；受害者 `ui/SetupWizard.kt:49-52`、`ui/AutomationCard.kt:35-51,53`、`ui/TunerApp.kt:32-34`
- 描述：`hasSecureGrant` 只在 ViewModel init 协程里赋值一次。真实旅程：安装 → 首启向导第一步显示授权命令（"用 PC 执行一次性授权命令解锁下方自动化功能"）→ 用户摘下头显去 PC 执行 `pm grant` → 回到 app——**授权已生效，但 UI 的所有快照仍是旧值**：
  1. 向导第二步仍显示"（未检测到授权…）"；
  2. AutomationCard 仍显示"未授予 WRITE_SECURE_SETTINGS"，四个自动化开关全部置灰（`grant=false`）；
  3. PrivateDnsSection 仍提示"需一次性授权后可用"。
  Quest 场景下 app 进程大概率仍存活（回后台不杀），且 `configChanges` 已声明（旋转不重建 Activity）→ **没有任何生命周期事件触发重新探测**。用户按向导指引完成授权却看不到任何变化，只能杀进程重开——而这一步没有任何 UI 提示。同理 `a11yRunning`（:71）与 `privateDnsMode`（:76）也是一次性快照。
- 证据：
  ```kotlin
  // TunerViewModel.kt:61-78 —— init 协程一次性快照，此后全 app 无第二处赋值
  init {
      viewModelScope.launch {
          ...
          hasSecureGrant = SecureAutomation.hasWriteSecureSettings(app),
          a11yRunning = SecureAutomation.isA11yRunning(app),
  // MainActivity 无 onResume/onStart 逻辑；VM 存活期内无重探测入口
  // AutomationCard.kt:53-57 —— grant=false 时开关全部 disabled
  val grant = state.hasSecureGrant
  SwitchRow(..., state.autoWifiAdb, grant) { ... }
  ```
  grep 核对：`hasSecureGrant` 写入仅 :66 一处；`setAutoA11y`（:171）虽直接调 `hasWriteSecureSettings` 判定行为，但不回写 state——开关区仍置灰。
- 建议验证方法：实机走完整旅程：装 app → 向导复制命令 → PC 执行 pm grant → 返回 app（不杀进程）→ 观察自动化区仍置灰；`adb shell dumpsys package com.example.questtuner | grep WRITE_SECURE` 显示已授予（系统侧已生效的证据对比）。修复方向：MainActivity `onStart` 回调触发 VM 重新探测（或生命周期观察器）；向导第一步加"授权后返回点此刷新"按钮作为最小改法。

## P2

### BUG-030 autoA11y/autoApply 状态与系统实态无对账，静默失效

- 级别：P2
- 位置：`ui/TunerViewModel.kt:71,167-210`；`ui/AutomationCard.kt:64-68`
- 描述：两个相互叠加的一致性缺口：
  1. **autoA11y 无自愈**：用户在未授权时（或向导里）打开"自动启用无障碍服务"→ prefs 持久 `autoA11y=true`，但 enable 失败（授权缺失/写入被拒）。之后用户完成授权（假设 BUG-029 修复后能刷新）或重启 app——init 只读实态 `isA11yRunning`（false）用于副文案，**没有任何路径重新执行 enableA11yService**。开关显示 ON、副文案"未运行（可在系统设置手动开启）"，承诺的"自动启用"静默失效，用户需手动 off/on 一次开关。对照：autoWifiAdb 在每次 connect 时活读 prefs 自然对账（SecureAutomation.kt:166），无此问题——不一致本身说明缺口。
  2. **autoApply 门禁条件失真**：`enabled = state.autoA11y || grant`（AutomationCard.kt:67）——grant=true 且用户从未启用无障碍服务时开关可打开，但服务未运行 → 无事件 → autoApply 完全静默失效（服务 `onAccessibilityEvent` 根本不会被调用）。真实依赖是"服务运行中"，UI 门禁与副文案均未表达。
- 证据：
  ```kotlin
  // TunerViewModel.kt:167-170 —— 开关只写 prefs+state，启动时无对账
  fun setAutoA11y(enabled: Boolean) {
      prefs.autoA11y = enabled
      _state.value = _state.value.copy(autoA11y = enabled)
      if (enabled) viewModelScope.launch { ... }   // 只在勾选瞬间尝试一次
  // init（:71）只读实态展示，不触发 re-enable
  a11yRunning = SecureAutomation.isA11yRunning(app),
  // AutomationCard.kt:67 —— 门禁条件与真实依赖（服务运行）不符
  state.autoApply, state.autoA11y || grant,
  ```
- 建议验证方法：未授权时开 autoA11y → PC 授权 → 重启 app（或修复 BUG-029 后刷新）→ 开关 ON 但服务未启用（系统设置可见）。修复方向：init/connect 成功后若 `autoA11y && grant && !isA11yRunning` → 自动补执行 enable；autoApply 副文案改为依据 a11yRunning 提示"无障碍服务未运行，自动应用不会触发"。

### BUG-031 NewProfileCard pickedLabel 陈旧：label 与 pkg 不匹配

- 级别：P2
- 位置：`ui/TunerApp.kt:162-166`（onSelect 赋值）、`:172-177`（pkg 可手改）、`:205`（保存取值）
- 描述：用户点"选择应用"选中应用 A（pkg="com.a"，pickedLabel="A"）→ 之后手动在包名输入框改成 "com.b"（onValueChange 只更新 pkg，pickedLabel 不清理）→ 保存：`label = pickedLabel ?: ...` = "A"，包名却是 com.b。Profile 列表显示错误应用名，autoApply 语义混淆（按 com.b 应用却显示 A 的名字）。选择器入口与手输共存的设计下必然出现。
- 证据：
  ```kotlin
  onSelect = { app -> pkg = app.packageName; pickedLabel = app.label; ... }
  ...
  onValueChange = { pkg = it },            // pickedLabel 未失效
  ...
  label = pickedLabel ?: pkg.ifBlank { "全局" },   // 陈旧 label 落盘
  ```
- 建议验证方法：UI 操作复现（选 A → 改包名 → 保存 → ProfileRow 显示 A 名 + com.b）。修复：onValueChange 中 `pickedLabel = null`，或保存时校验 label 来源包名一致。

### BUG-032 wifiAdbEnabledByUs 内存标记：进程被杀后无线调试回滚永不发生

- 级别：P2（含安全面：网络 ADB 监听残留）
- 位置：`tuner/SecureAutomation.kt:20-22`（@Volatile 内存标记）、`:69-74`（回滚入口）
- 描述：开关文案承诺"断开时自动恢复（仅关闭由本应用开启的）"。但 `wifiAdbEnabledByUs` 是 object 内存变量：connect 时自动开启无线调试（标记 true）→ **Quest 后台杀进程（连接态挂后台时常见）→ 标记随进程消失**。之后：
  1. 无任何时机调用 `disableWifiAdbIfWeEnabled`（它只在显式 disconnect 与 BootReceiver 收尾调用）；
  2. 下次打开 app：executor/connection 全新、UiState.connected=false → **断开按钮 disabled，UI 上不存在触发回滚的路径**；
  3. 无线调试保持开启（adbd 网络监听残留），直到用户再 connect→disconnect 一轮（标记重新置位后才回滚）——这个恢复动作极不直觉。
  进程被杀是 Android 常态事件，"按需恢复"承诺在最需要它的场景（异常退出）恰好失效。
- 证据：
  ```kotlin
  // SecureAutomation.kt:20-22 —— 内存标记，无持久化
  @Volatile
  var wifiAdbEnabledByUs = false
      private set
  // 回滚仅两处调用：VM.disconnect / BootReceiver 收尾；进程死亡后均不可达
  ```
- 建议验证方法：授权+autoWifiAdb 开 → 连接（确认系统无线调试已开）→ `adb shell am kill com.example.questtuner` 模拟后台杀 → 系统设置确认无线调试仍开 → 重开 app：断开按钮置灰，无回滚路径。修复方向：标记持久化到 prefs（enable 时写 true，回滚后清），app 启动时若 `标记=true && 用户未显式连接` → 对账回滚（注意与用户手动开启的区分：只在标记为我们所置时回滚）。

### BUG-033 i18n 与无障碍语义基础缺失

- 级别：P2（若产品明确只面向中文用户，可与 lead 确认后降为优化项执行）
- 位置：全部 UI 文件（TunerApp/AutomationCard/SetupWizard/UpdateCard/AppPickerDialog 共 ~70 处硬编码中文）；`res/values/strings.xml`（仅 2 条 a11y 文案）；`ui/TunerApp.kt:192-197`（Slider 无语义绑定）
- 描述：
  1. **文案资源化不一致**：仅无障碍服务 description/label 走 strings.xml（系统设置页需要资源引用，属被迫），其余全部 UI 文案硬编码在 Composable 里——locale 切换完全无效，也无法做英文 fallback；与既有资源化形成半吊子状态。
  2. **无障碍语义**：`Image(contentDescription = null)` 为装饰性图标可接受；但 NewProfileCard 的四个 Slider（刷新率/纹理/CPU/GPU）与其标签 Text 是兄弟节点无 `semantics {}` 绑定——TalkBack 聚焦 Slider 时读不出"刷新率"等含义；SwitchRow 同理（文本与 Switch 无关联）。项目自身声明了 a11y 服务与隐私文案，却未保证自身 UI 的 TalkBack 可用性，自洽性欠佳。
- 证据：`strings.xml` 全文 2 条；`grep` 中文文案在 ui/ 下 70+ 处直接内联；Slider 无 semantics modifier。
- 建议验证方法：系统语言切英文 → UI 全中文（确认无 locale 响应）；TalkBack 开启 → 聚焦任一 Slider → 只读"滑块"不读用途。修复方向：文案迁移 strings.xml + `Modifier.semantics { contentDescription = ... }` 绑定；若只做中文，加 `resourceConfigurations("zh")` 收缩并记录决策。

---

## 优化建议（不阻塞）

| # | 位置 | 建议 |
|---|---|---|
| R4-O1 | SelfUpdater update/ 目录 | 跨 tag 的陈旧下载文件累积（同 tag 覆盖、终态删除均有，但换版本后旧 APK 残留）：downloadAndInstall 启动时清空 update/ 目录中非当前 tag 文件 |
| R4-O2 | TunerViewModel.cancelConnect | 取消竞态收尾：cancel 后若 executor 晚到完成连接（阻塞 read 不响应取消），出现"连接活但 sessionActive=false"的中间态——cancelConnect 里追加 `TuningExecutor.disconnect()` 兜底 |
| R4-O3 | TunerViewModel init | wizardVisible 首帧闪烁：UiState 默认 false，init 协程一帧后才置 true——首帧主界面闪现再弹向导；可在 VM 构造时同步读 `prefs.firstRunDone`（SP 首读开销可忽略） |
| R4-O4 | AutomationCard autoApply 副文案 | 与 BUG-030 联动：副文案按 a11yRunning 实时提示"服务未运行，不会自动应用"，消除静默失效 |
| R4-O5 | SetupWizard 第二步 | WizardSwitch 无 grant/依赖门槛（与 AutomationCard 的 enabled 语义不一致）：可勾选但不生效——统一禁用语义或加即时提示 |
| R4-O6 | Profile 校验 | 保存 Profile 时校验 pickedLabel 与 pkg 一致性（BUG-031 的防御性替代/补充） |
| R4-O7 | adbStatus 覆盖链 | applyProfile 失败 → refreshStatus 再失败 → 两条"连接已断开"文案先后覆盖；合并为单一状态发布或去重，避免状态栏闪烁 |
| R4-O8 | AppPickerDialog | 搜索无结果时列表空白无提示（filtered 为空 → LazyColumn 空）：加"无匹配应用"占位文案 |

## 已核对无问题（本轮四条线的通过项，避免复查重复）

- **旅程·连接成功态**：connectionState collector 与 connect() 成功分支的两种执行顺序推演均收敛到正确终态（collector 先行则被成功分支覆盖 adbStatus；后行则条件不满足 no-op）——round-3 引入的同步机制在正常旅程无文案错乱。
- **旅程·连接中取消安全性**：connecting 态断开按钮 disabled（connected=false），不存在"断开/连接并发"；取消按钮即 cancelConnect，UI 门禁正确。
- **旅程·更新中途进程被杀**：PackageInstaller 会话已 commit → manifest 注册的 InstallResultReceiver 仍收终态 → SelfUpdater 单例 StateFlow → 新 VM collect 得到结果；下载中被杀 → 部分文件同 tag 覆盖。自洽。
- **Prefs 三处读取一致性**：VM/服务/接收器（含 SecureAutomation）全部经同一 SP live 读取，键名与默认值（全 false）三方一致；autoApply=false 服务 no-op、restoreOnBoot=false 接收器 no-op、autoWifiAdb=false 连接跳过——行为矩阵正确（BUG-030 报的是对账缺失，非读取不一致）。
- **进程死亡·会话语义**：adbSessionActive 内存态随进程死重置为 false → 服务 no-op 等待显式连接——保守正确（PLAN-round3 已论证）。
- **BootReceiver**：不 enable 无线调试（只 ensureConnected），30s 轮询后 disconnect + disableWifiAdbIfWeEnabled（no-op）——与 BUG-032 场景无叠加；best-effort 语义文档与实现一致。
- **round-3b 遗留**：generation 单测边界已在测试注释中说明（依赖单例无法纯测，改为快照忠实性验证）——按任务指示复核确认，无需改动。
- **strings.xml 既有 2 条**：a11y description/label 内容与隐私承诺一致（"不读取屏幕内容/不上传"），无夸大。

## 修复优先级建议

1. **BUG-029**（P1，授权旅程死路——它同时放大 BUG-030 的复现概率，建议一并修：刷新时机同为"重新探测"）。
2. **BUG-032**（P2 带安全面，持久化标记 + 启动对账，改动集中在 TunerPrefs/SecureAutomation/VM 启动）。
3. BUG-030/031（一致性/数据正确性，小改动）。
4. BUG-033（先与 lead 确认产品定位：仅中文 → 执行 R4-O7 的 resConfigs 方案；否则全量资源化）。
