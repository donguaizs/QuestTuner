# QuestTuner Round 3 Bug 报告（round-2 新代码审查）

- 审查人：David（QA）
- 日期：2026-10-24
- 审查对象：master @ fe36083（round-2 已合入，app/ 与 fix/round-2 逐字节一致——已用 `git diff master fix/round-2 -- app/` 验证 0 差异）
- 范围：round-2 新增/修改的 22 个文件（SecureAutomation、ForegroundProfileService、SelfUpdater、InstallResultReceiver、OvrMetrics、PrivateDns、BootReceiver、TunerPrefs、SetupWizard、AutomationCard、AppPickerDialog、UpdateCard、TunerViewModel/TunerApp 接线、manifest、R8 配置），以及与 round-1 修复的交互回归
- 依据：PLAN-round2.md 假设台账 A1-A9、VERIFY-round2.md 的 RISK-1~4（按 lead 指示升级确认）
- 编号延续 round-1（BUG-001…018），本轮从 BUG-019 起

## 汇总

| 编号 | 级别 | 位置 | 摘要 |
|---|---|---|---|
| BUG-019 | **P1** | ForegroundProfileService.kt:44-53 | 应用切换事件并发 launch 无顺序保证：快速「开应用→退桌面」时 apply 与 resetAll 乱序执行，桌面态残留游戏参数且去重机制阻止自愈 |
| BUG-020 | P2 | ForegroundProfileService.kt:48-53 + TunerApp.kt:103-113 | autoApply 的 resetAll 会清掉用户**手动**应用的参数——违反 PLAN「绝不重置用户手动设置」承诺 |
| BUG-021 | P2 | SelfUpdater.kt:75-79 | 自更新下载缺纵深防御：URL 无 host 白名单 + 无下载大小上限（VERIFY RISK-3 升级） |
| BUG-022 | P2 | SelfUpdater.kt:104-105 | updateApk 用 GitHub tag 拼文件路径，tag 可含 `/`（git 允许）→ 路径穿越，恶意/异常 tag 可把下载内容写到 update/ 目录外 |
| BUG-023 | P2 | TunerViewModel.kt:347-357 | verifyApk/install（解析 APK 签名、复制文件）在 Main dispatcher 执行——主线程重 IO，违背 round-1 BUG-016 确立的原则 |
| BUG-024 | P2 | SelfUpdater.kt:141-159 | install() 无异常保护：openWrite/commit 抛 IOException（磁盘满等）→ downloadAndInstall 协程内未捕获 → **应用崩溃** |
| BUG-025 | P2 | SecureAutomation.kt:100-112 | disableA11yService 写入后无回读验证（enable 有、disable 无），写入静默失败时开关状态与系统实态脱节（VERIFY RISK-4 升级） |
| BUG-026 | P2 | TunerViewModel.kt:86-112 + SecureAutomation.kt:157-160 | 授权等待场景下 connect 最坏 4×35s≈140s 无总超时、无取消途径（round-1 RISK-3 被 round-2 的 4 次重试循环放大） |
| BUG-027 | P2 | TunerViewModel.kt:114-125 + ForegroundProfileService.kt:58 | 用户「断开」（含回滚无线调试）后，autoApply 服务在前台切换时自动重连并**重新开启无线调试**——逆转用户意图且 UI 无感知 |
| BUG-028 | P2 | ForegroundProfileService.kt:25-31 | onServiceConnected 若被系统再次回调，旧 CoroutineScope 被覆盖而未 cancel——协程与资源泄漏 |

优化建议（不阻塞，10 项）见专门章节。

---

## P1

### BUG-019 前台切换命令乱序：桌面态残留应用参数且无法自愈

- 级别：P1
- 位置：`app/src/main/java/com/example/questtuner/tuner/ForegroundProfileService.kt:44-53`（配合 :56-67）
- 描述：`onAccessibilityEvent` 对「进入有 profile 的应用」和「离开（去桌面/无 profile 应用）」分别 `scope.launch` 两个协程，两者最终都通过 `TuningExecutor` 执行 setprop。`TuningExecutor.adbMutex` 只保证**互斥**，不保证**FIFO 顺序**：ReentrantLock 非公平锁 + 两个协程的启动/调度顺序不确定。典型场景「打开游戏 A 后立即按 Home」：
  1. 事件 A（游戏窗口）：`lastAppliedPkg=A`，launch **applyA**（多组 setprop）
  2. 事件 B（launcher，800ms 节流内或紧随）：`lastAppliedPkg=null`，launch **resetAll**
  3. 两协程竞争 adbMutex：若 **resetAll 先获得锁、applyA 后执行** → 用户已在桌面，全局属性却是 A 的 profile 值（刷新率/分辨率/CPU/GPU 全部）
  4. 更糟的是**无法自愈**：`lastAppliedPkg` 已是 null，后续 launcher/无 profile 包事件命中 `else if (lastAppliedPkg != null)` 为 false，不再触发任何命令——错挂状态持续到用户下次切换到有 profile 的应用。
  「开应用秒退」是高频操作（误触、回头找东西），桌面态挂高功耗参数直接违背产品目的（省电/保真切换）。同族问题：快速 A→B 切换时 applyA 后执行导致 B 前台挂 A 参数（此场景 B 自身事件已 launch applyB，最终顺序仍可能错，但多次事件有概率纠正；桌面态是最坏且不自愈的路径）。
- 证据：
  ```kotlin
  // ForegroundProfileService.kt:44-53 —— 两个分支各自 launch，顺序无保证
  val profile = r.find(pkg)
  if (profile != null) {
      lastAppliedPkg = pkg
      scope.launch { applyOrRetry(pkg, profile.toCommands()) }     // ① apply
  } else if (lastAppliedPkg != null) {
      val prev = lastAppliedPkg
      lastAppliedPkg = null
      prev?.let { scope.launch { applyOrRetry(it, TuningCommands.resetAll()) } }  // ② reset
  }
  // TuningExecutor.kt adbMutex = Mutex() —— 互斥但不保证等待者 FIFO
  ```
- 建议验证方法：
  1. 单测/伪 shell：并发 launch apply 与 reset 各 N 次记录执行序，断言与事件序一致（当前必现乱序窗口）；
  2. 实机：autoApply 开启、给某 VR 应用建 profile → 冷启动它并在 1 秒内按 Home → `getprop debug.oculus.refreshRate`：应恢复默认却保留 profile 值；再切到无 profile 的其他应用，属性仍不恢复（不自愈证据）。
  3. 修复方向（供 Li 参考）：单线程串行通道（Channel/单 worker 协程消费事件队列，天然 FIFO）；或每命令携带序号、执行前校验自己是最新意图（旧意图跳过）。

## P2

### BUG-020 autoApply 的 resetAll 清掉用户手动设置的参数

- 级别：P2
- 位置：`ForegroundProfileService.kt:48-53`；交互对象 `TunerApp.kt:100-113`（QuickTune）、`TunerViewModel.applyProfile`
- 描述：PLAN-round2 §P0-B 风险段承诺「只撤销自己做过的改动…绝不重置用户手动设置之外的状态」，但实现上服务的回滚粒度是 `resetAll()`（清空全部 debug.oculus.*），且触发条件只看自己的 `lastAppliedPkg`。场景：autoApply 开启 → 服务曾应用过游戏 B 的 profile（lastAppliedPkg=B 被清除的前提是先离开 B）→ 实际可复现路径：服务应用 B → 用户回到 QuestTuner UI 手动点「全局 90Hz」或 QuickTune（手动 setprop，不经过服务）→ 用户切到 launcher/任意无 profile 应用 → 服务执行 resetAll → **用户的手动 90Hz 被无声清除**。服务无法区分手动值与自己上次设的值。
- 证据：
  ```kotlin
  // 手动路径（TunerApp QuickTune / applyProfile）与服务共享全局属性，无任何互斥/标记
  // ForegroundProfileService.kt:52 —— 回滚即全局清空
  prev?.let { scope.launch { applyOrRetry(it, TuningCommands.resetAll()) } }
  ```
- 建议验证方法：autoApply 开 + 给应用 B 建 profile → 打开 B（服务应用）→ 回 UI 手动应用「全局 120Hz」→ 按 Home → getprop refreshRate 应为 120 实际变回空。修复方向：resetAll 前先 getprop 比对当前值是否仍等于自己应用过的 profile 值（不一致则跳过回滚），或 UI 手动应用时通知服务重置 lastAppliedPkg。

### BUG-021 自更新下载缺纵深防御：URL host 无白名单 + 无大小上限

- 级别：P2（安全加固；攻击前提为仓库/账号被接管，四重校验兜底包内容）
- 位置：`update/SelfUpdater.kt:75-79`（download）、:55-60（ReleaseInfo 构造）
- 描述：VERIFY-round2 RISK-3 确认成立，按 lead 指示升级。两个缺口叠加：① `browser_download_url` 直接使用，未校验 host ∈ GitHub 域（github.com / objects.githubusercontent.com）——API 响应可指向任意外部服务器（做流量探测或投毒，投毒被签名校验拦截但下载本身已完成）；② `download()` 无大小上限，`contentLengthLong` 仅用于进度条——恶意/异常资产可无限写满外部存储（app 专属目录，但占满共享卷影响系统）。
- 证据：
  ```kotlin
  // SelfUpdater.kt:75-79 —— URL 直连，无 host 校验、无 max size
  val conn = URL(url).openConnection() as HttpURLConnection
  ...
  val total = conn.contentLengthLong   // 只用于进度
  // ReleaseInfo.size 记录了 asset size 但从未用于上限判断
  ```
- 建议验证方法：本地 mock API 返回指向局域网大文件的 browser_download_url → 当前直接下载；修复后应拒绝非白名单 host，且超过上限（如 50MB，正常 APK <5MB 量级）中止并删文件。

### BUG-022 updateApk 文件名拼接 GitHub tag 未净化 → 路径穿越

- 级别：P2（安全加固，同 BUG-021 攻击前提）
- 位置：`update/SelfUpdater.kt:104-105`
- 描述：`File(File(...,"update"), "questtuner-$tag.apk")`——git tag 允许包含 `/`（如 `v0.3/../../evil`）。tag 含分隔符时构造的路径逃出 `update/` 目录，下载内容（攻击者控制的字节流）写到外部存储任意可写路径；即使后续 verifyApk 失败会 delete，穿越路径的文件写入/短暂存在已构成副作用（且 delete 的是同一穿越路径，若攻击者构造不可删场景更麻烦）。`tag_name` 来自 API JSON，不应信任。
- 证据：
  ```kotlin
  fun updateApk(context: Context, tag: String): File =
      File(File(context.getExternalFilesDir(null), "update"), "questtuner-$tag.apk")
  // tag = "a/../../b" → questtuner-a/../../b.apk → 解析后逃逸 update/
  ```
- 建议验证方法：单测构造 tag="../../x" 断言 updateApk 的 canonicalPath 仍在 update/ 下；修复：tag 净化（仅 [A-Za-z0-9._-]，或改用 asset id）。

### BUG-023 verifyApk / install 在主线程执行（StrictMode 违规）

- 级别：P2
- 位置：`ui/TunerViewModel.kt:347-357`（downloadAndInstall 的 onSuccess 块）
- 描述：`downloadAndInstall` 在 `viewModelScope.launch`（Main）中运行；`download()` 内部有 `withContext(Dispatchers.IO)`，但 `.onSuccess { }` 块回到 Main 后直接调用 `SelfUpdater.verifyApk()`（getPackageArchiveInfo 解析 APK + 读签名证书，磁盘重 IO）和 `SelfUpdater.install()`（文件复制进 session）——全部阻塞主线程。2MB APK 实测量级为几十~几百 ms，低端或大 APK 场景掉帧/ANR 风险；同时违背 round-1 BUG-016 确立并修复过的「磁盘 IO 不上主线程」原则（同类回归）。
- 证据：
  ```kotlin
  // TunerViewModel.kt:347-352 —— onSuccess 回到 Main dispatcher
  }.onSuccess { f ->
      SelfUpdater.verifyApk(ctx, f).onSuccess {      // ← Main 线程解析 APK
          ...
          SelfUpdater.install(ctx, f)                  // ← Main 线程复制文件
  ```
- 建议验证方法：StrictMode penaltyLog 开启后走更新流程，logcat 出现 DiskReadViolation；修复：verifyApk/install 包 withContext(Dispatchers.IO)。

### BUG-024 install() 无异常保护，磁盘满等 IOException 直接崩溃

- 级别：P2
- 位置：`update/SelfUpdater.kt:141-159`；调用方 `TunerViewModel.kt:352`
- 描述：`install()` 内 openSession/openWrite/copyTo/commit 均可能抛 IOException（磁盘满、session 打开失败等），函数无 runCatching，调用链 `downloadAndInstall` 的协程内也无 try-catch → 异常未捕获进入 viewModelScope → **进程崩溃**。对照同文件 `requestInstallPermission` 有 runCatching，防御不一致。自更新是后台增强功能，不应有 crash 路径。
- 证据：
  ```kotlin
  fun install(context: Context, apk: File) {
      ...
      session.openWrite("questtuner", 0, -1).use { out ->
          apk.inputStream().use { it.copyTo(out) }   // ← 可抛 IOException，未捕获
  ```
- 建议验证方法：单测/实机模拟外部存储不可写（或 stub）→ downloadAndInstall 应更新 updateStatus 为失败而非 crash；修复：install 返回 Result 或整体 runCatching + notifyInstallResult(false, …)。

### BUG-025 disableA11yService 写入后无回读验证（探测纪律不对称）

- 级别：P2
- 位置：`tuner/SecureAutomation.kt:100-112`
- 描述：VERIFY-round2 RISK-4 确认成立，升级为正式条目。`enableA11yService` 有「写入→回读→WriteRejected」三段式探测（:94-96），`disableA11yService` 写入 `rest` 后直接返回 `CapProbe.Ok`——若 putString 静默失败（返回 false 不抛异常），开关 UI 显示已关闭而系统无障碍列表里服务仍在。违背 PLAN「每个操作 = 尝试 + 回读探测」的自身纪律。
- 证据：
  ```kotlin
  Settings.Secure.putString(cr, "enabled_accessibility_services", rest)
  if (rest.isEmpty()) Settings.Secure.putString(cr, "accessibility_enabled", "0")
  CapProbe.Ok    // ← 未回读；对照 enable 分支 :94-96 的回读验证
  ```
- 建议验证方法：stub Settings 使 putString 返回 false → disable 应返回 WriteRejected；修复：回读确认 flat 已不在列表，否则 WriteRejected。

### BUG-026 连接重试循环放大授权等待：最坏 ~140s 无总闸、无取消

- 级别：P2（UX 健壮性）
- 位置：`ui/TunerViewModel.kt:86-112`（connect）+ `tuner/SecureAutomation.kt:157-160`（4 次重试）+ `TuningExecutor.ensureConnected`（withTimeout 35s）
- 描述：round-1 的授权等待超时封顶 35s；round-2 把连接入口换成 `ensureWifiAdbThenConnect` 的 4 次重试循环（间隔 1.5s）。若 adbd 存在但 AUTH 等待用户确认（soTimeout 30s / withTimeout 35s 单次失败），循环每次都重新 TCP+AUTH：最坏 4×35s + 3×1.5s ≈ **140s** 全程 `connecting=true`——按钮禁用、无取消途径、状态文案停在「已自动开启无线调试，等待 adbd…」。用户在头显上慢慢犹豫是否授权时，UI 长时间假死感。
- 证据：
  ```kotlin
  // SecureAutomation.kt:157-160
  repeat(4) { attempt ->
      if (TuningExecutor.ensureConnected(context)) return@withContext true
      if (attempt < 3) delay(1_500)
  }
  // TunerExecutor.ensureConnected 内 withTimeout(35_000L) 仅约束单次
  ```
- 建议验证方法：未授权实机连接且不点确认 → 观察卡顿 2 分钟；修复：循环对「超时类失败」短路（重试只针对 TCP refuse），或 connect() 加总超时与取消按钮。

### BUG-027 用户「断开」被 autoApply 服务逆转：重连 + 重开无线调试，UI 无感知

- 级别：P2（语义/隐私控制权）
- 位置：`ui/TunerViewModel.kt:114-125`（disconnect）+ `tuner/ForegroundProfileService.kt:58`（服务内 ensureWifiAdbThenConnect）+ `SecureAutomation.kt:68-73`
- 描述：用户点「断开」的语义是停止 ADB 活动（并回滚我们自动开启的无线调试）。但 autoApply 开启时，下一次前台切换服务的 `applyOrRetry` 会再次 `ensureWifiAdbThenConnect`——**重新开启无线调试（marker 再次置位）+ 重建连接 + 执行 setprop**，全程无 UI 提示（服务静默，adbStatus 仍是「已断开」）。用户以为一切已停，实际后台通道被重新打开；配套状态也脱节：executor.connection 非空而 UiState.connected=false。附带：`wifiAdbEnabledByUs` 是服务与 UI 共享的单例标记，并发 enable/disable 交错时「谁开的」归属可能错乱（低概率，影响仅限回滚边界）。
- 证据：
  ```kotlin
  // disconnect（UI）：
  TuningExecutor.disconnect()
  SecureAutomation.disableWifiAdbIfWeEnabled(...)   // 关掉我们开的无线调试
  // 服务（前台切换即触发，与用户意图冲突）：
  val ok = SecureAutomation.ensureWifiAdbThenConnect(ctx) && TuningExecutor.runCommands(cmds)
  ```
- 建议验证方法：授权 + autoWifiAdb + autoApply 全开 → 连接 → 断开 → 确认无线调试已关 → 打开任一有 profile 的应用 → `getprop` 有值且系统设置里无线调试又被打开。修复方向：disconnect 时同时暂停 autoApply 会话（会话级 running 标志，服务检查），或服务建连后通知 UI 同步状态。

### BUG-028 onServiceConnected 重复回调时旧 scope 未取消（协程泄漏）

- 级别：P2（低概率）
- 位置：`tuner/ForegroundProfileService.kt:25-31`
- 描述：`onServiceConnected` 每次回调都直接覆盖 `scope/repo/prefs`；若系统对同一服务实例再次回调（a11y 重连/崩溃恢复路径，厂商行为差异），旧 `CoroutineScope`（SupervisorJob + IO）被覆盖且未 cancel——旧 scope 内在途的 applyOrRetry 协程变成孤儿：无法通过 onDestroy 取消（onDestroy 取消的是新 scope），其 shell 会话仍占 adbMutex 排队。正常流程一实例一连接，故低概率，但防御成本一行。
- 证据：
  ```kotlin
  override fun onServiceConnected() {
      super.onServiceConnected()
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)  // 旧 scope 未 cancel 即覆盖
  ```
- 建议验证方法：代码走查即可确认；修复：赋值前 `scope?.cancel()`。

---

## 优化建议（不阻塞合入；按收益排序）

| # | 位置 | 建议 |
|---|---|---|
| O1 | SecureAutomation.kt:115 | `waitA11yRunning` 3s 窗口对慢固件偏紧（VERIFY RISK-1）：延长至 5s 或 200ms→500ms 指数退避，减少「已写入但未运行」误报 |
| O2 | BootReceiver.kt:26-34 | 开机 adbd 就绪竞态（VERIFY RISK-2）：4×1.5s 重试改为总时限轮询（如 30s 内每 3s 试一次），覆盖系统启动慢的 adbd |
| O3 | AutomationCard.kt:106 | `OvrMetrics.isAvailable` 每次重组都发 PM IPC：包 `remember { }` 缓存（安装状态在 app 生命周期内基本不变） |
| O4 | AppPickerDialog/TunerApp.kt:178-181 | 选择器无 loading 态：apps 为空时点击后先显示「加载中…」，且加载期间禁用「加载应用列表」按钮防重复 IPC |
| O5 | PrivateDns.kt:28 | host 写入前做基本格式校验（RFC 1123 主机名正则 + trim），拦截 "a b"、带 scheme 等必然失败的输入 |
| O6 | SelfUpdater.kt:168-178 | `versionGreaterThan` 对 pre-release tag 语义未定义（"0.3.0-beta"==0.3.0 → 永不更新；"release-0.3" 前缀 → 永不更新）：文档化 tag 命名约定（`vX.Y.Z`）或实现 semver pre-release 比较 |
| O7 | UpdateCard.kt:32 | 「检查更新」无防重入：checking 态禁用按钮（连点会并发多个 checkLatest，状态闪烁） |
| O8 | UpdateCard.kt:23 | 文案「当前 手动检查更新（GitHub Releases，HTTPS）」别扭：updateStatus 为空时省略「当前」前缀 |
| O9 | ForegroundProfileService | applyOrRetry 无并发上限：连接失败时快速反复切换会堆积连接尝试协程（串行排队但队长无界）——加并发信号量或失败后冷却窗口（如 10s） |
| O10 | TunerViewModel | 服务侧建连/断连后 UI 无感知（executor 实态与 UiState.connected 脱节，见 BUG-027 根因之一）：executor 暴露连接状态回调或 UI 恢复时重新探测 `connection != null` |

## 已核对无问题（避免复查重复劳动）

- **SetupWizard 重复弹出（任务点名项）**：无缺陷。`firstRunDone` 持久化正确、dismissWizard 原子写；向导期间进程被杀重弹属「未完成配置」合理语义；`onDismissRequest={}` 强制走按钮是刻意的引导设计。
- **AutomationCard 与 ConnectionCard 冲突（任务点名项）**：无直接冲突。断开按钮 connected 门禁正确；开关只写 prefs 下次连接生效，语义与文案一致；唯一交叠是 BUG-027 的服务侧重连。
- **R8 keep**：round-3 无新改动，VERIFY-round2 的产物级验证（4 组件原名保留 + a11y xml 链完整 + 0 tooling 残留）仍然有效；工程无反射/序列化动态引用。
- **SharedPreferences 一致性**：TunerPrefs 每次 get 新建轻对象但底层 SP 是系统同名单例；apply() 的内存可见性对同进程 receiver/service 足够。无跨进程需求。
- **installResult 收集**：VM 重建时旧 collector 随 viewModelScope 取消，StateFlow consume 语义正确。
- **下载取消路径**：download 的 runCatching 捕获 CancellationException 后删文件并**重抛**——取消语义正确传播，无文件残留、无吞取消。
- **PrivateDns off() 的 specifier 残留**：符合 AOSP 行为（mode=off 时忽略 specifier，系统设置同样残留），非缺陷。
- **A1-A8 假设台账实现**：与 VERIFY-round2 核对结论一致，无未按台账实现的偏离；本轮新发现均为台账未覆盖的实现层缺陷。
- **round-1 修复回归**：adb/ 包与 TuningExecutor 核心未被 round-2 改动破坏；BUG-023 是 round-1 BUG-016 同类原则在新代码上的回归（新代码引入，非旧修复失效）。

## 修复优先级建议

1. **BUG-019**（P1，核心自动化路径功能性错误）——建议 Li 评估 Channel 串行队列方案，修复时连带考虑 BUG-020（同文件同机制，resetAll 前值比对可一并解决）。
2. **BUG-024 + BUG-023**（crash 路径 + 主线程 IO，同在更新链，一次修复）。
3. **BUG-021 + BUG-022**（更新链安全加固，一次修复：host 白名单 + tag 净化 + 大小上限）。
4. **BUG-027**（断开语义，与 019 的会话标志方案有交集）。
5. BUG-025/026/028 与 O1-O10 按余量。
