# QuestTuner Round 3 修复验证报告（VERIFY-round3）

- 验证人：David（QA）
- 日期：2026-10-24
- 修复分支：`fix/round-3`（master..fix/round-3 共 9 commits，f5700e9..9004c09）
- 方案依据：docs/plans/PLAN-round3.md（Li）；bug 依据：docs/bugs/BUG-REPORT-round3.md（BUG-019~028 + O1-O10）
- 验证方式：逐 commit diff 审查 + 5 个重点专项深审 + clean 三件套构建 + 单测结果核对

## 总体结论

**9/10 项 PASS，1 项 FAIL（BUG-020 的回滚路径因 PLAN 自身缺陷而完全失效，需小修补）。不建议直接合入，修复 FAIL 项后可合（其余 9 项无需返工）。**

- 构建：`clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 37s**
- 单测：**21/21 通过**（IntentFold 3 + MergeA11y 4 + ValuesMatch 5 + UpdateSafety 4 + VersionCompare 5）
- FAIL 项本质：PLAN-round3 的 BUG-020 设计中 `stillOurValues()` 依赖 `lastApplied`，但事件入口在入队 `Leave` 时已把它置 null——**Wang 忠实实现了有缺陷的方案**，责任在方案不在实施。单测只覆盖了纯函数 `valuesMatch`（其本身正确），未覆盖"Leave 执行时 lastApplied 已置空"的时序，故测试全绿但路径失效。

## 逐项验证结论

| 编号 | 结论 | 对应 commit(s) |
|---|---|---|
| BUG-019 | **PASS**（乱序已修复；但其目标场景被 BUG-020 FAIL 抵消，见下） | 12a129c |
| BUG-020 | **FAIL**（值比对机制正确但永远不触发——回滚路径死亡） | 12a129c |
| BUG-021 | **PASS**（附 scheme 小观察） | 2ece623 |
| BUG-022 | **PASS** | 2ece623 + 9004c09 |
| BUG-023 | **PASS** | 5bd6d7a |
| BUG-024 | **PASS** | 5bd6d7a |
| BUG-025 | **PASS** | 370ebc9 |
| BUG-026 | **PASS** | 41f5f09 |
| BUG-027 | **PASS** | f5700e9 + 69e37c4 |
| BUG-028 | **PASS** | 12a129c |
| O1-O8 | **PASS**（O4 附 finally 小观察；O9/O10 按方案处理） | 05afaf9 等 |

---

## 重点专项详审

### 专项① BUG-019 并发正确性 — **PASS**（但价值被 ② 抵消）

**单 worker FIFO**：`Channel(16)` + `for (intent in q)` 单消费者——Channel 天然保序，事件序 == 执行序 ✅。事件入口只 `trySend`（非阻塞），不再 launch 多协程竞争 adbMutex ✅。

**意图折叠正确性（含"合法序列折叠错"审查）**：worker 取一个意图后 `tryReceive` 循环折叠到最新。我推演了关键序列：
- 快速 A→B→桌面（A/B 有 profile）：队列 [Apply(A), Leave(A), …] → 折叠执行最终意图 ✅
- A→B→A（最终该留在 A 参数）：[Apply(A), Leave(A), Apply(A)] → 折叠后执行 **Apply(A)** ✅ 最终态正确——「该留在游戏参数」的合法序列不会被折叠错，因为折叠保留的是**最后一个**意图，而事件流最后一项正是最终态。
- IntentFoldTest 用真实 Channel 验证折叠行为（3 用例过）✅

**worker 生命周期**：
- onDestroy：queue.close() + scope.cancel()——缓冲意图随之丢弃，服务销毁场景可接受（下次连接重建）✅
- onServiceConnected 重复回调：旧 scope.cancel() + 旧 queue.close() 后重建（BUG-028）✅
- execute 外 runCatching（PLAN 风险段要求的最后防线）✅ worker 不会因异常终止 for 循环

**容量边界**：trySend 满 16 丢弃的是**最新**意图（旧的已入队）——注释表述（"丢弃旧意图"）与实际相反，但后果相同：过渡态被执行、最终态等下一次窗口事件纠正。前台切换频率远低于 16，实际风险极低。可接受。

### 专项② BUG-020 比对可靠性与代次竞态 — **FAIL**

**值比对机制本身正确**：
- `valuesMatch` 纯函数：分段序与 statusCommand 六段一致（refreshRate/textureWidth/textureHeight/cpuLevel/gpuLevel/highPrecisionPassthrough）、`split("---").map { trim() }` 处理 echo 分段空白、只比对 profile 设置过的项——5 个单测覆盖全部分支 ✅
- 前导零/空格：A10 假设（getprop 同值往返）已在台账登记，trim 消除了分段空白风险 ✅
- manualGeneration 代次：VM applyProfile/resetAll bump ✅、AppliedRecord 记录快照代次 ✅、stillOurValues 校验 ✅；「服务 Apply 后、Leave 执行前」的手动应用窗口被代次机制覆盖 ✅

**但整条路径是死的**。致命时序：

```kotlin
// ForegroundProfileService.kt:96-99（事件入口，入队 Leave 时）
} else if (lastApplied != null) {
    val prev = lastApplied!!
    lastApplied = null                      // ← ① 先置 null
    q.trySend(ApplyIntent.Leave(prev.pkg))  // ← ② 意图只携带了 pkg 字符串！
// ForegroundProfileService.kt:148-149（worker 执行 Leave 时）
private suspend fun stillOurValues(): Boolean {
    val rec = lastApplied ?: return false   // ← ③ 此时 lastApplied 已是 null → 恒 false
```

**后果**：`execute(Leave)` 的第一行 `if (!stillOurValues())` 恒真 → **log "skip rollback" 后直接 return，resetAll 永远不执行**。即：
1. BUG-020 的修复目标（回滚前比对）成为死代码；
2. **连带使 BUG-019 的核心验收场景失效**——「开应用 → 按 Home → 参数恢复默认」现在变成「永不恢复默认」（与修复前 BUG-019 的『桌面残留参数』表象相同，只是成因从乱序变成回滚被跳过）；
3. 唯一能让 `lastApplied` 非空的竞态（Leave 在队、紧随的 Apply(B) 又写入 lastApplied）反而会用 **B 的记录**回答 **A 的回滚**问题——语义错乱。

**根因归属**：PLAN-round3 §BUG-019 (d) 与 §BUG-020 的设计即如此（入队时置 null + stillOurValues 读共享字段）——**方案缺陷，Wang 忠实实现**。单测只测了 `valuesMatch` 纯函数，未覆盖该时序，所以 21/21 绿灯没能拦截。

**修复建议（最小改动，供 Li 参考）**：`ApplyIntent.Leave` 携带完整 `AppliedRecord`（入队时快照 pkg/profile/generation），`stillOurValues(record)` 比对意图内快照而非共享可变 `lastApplied`——一行结构改动，同时天然消除 PLAN 风险段承认的「lastApplied 跨线程交错」问题。补一个时序单测（入队 Leave 后 lastApplied 已 null，断言 Leave 仍能凭意图内快照比对）。

### 专项③ BUG-027 三态语义 — **PASS**

- `adbSessionActive` 状态机核对：connect 成功 → `markSessionActive()`（TunerViewModel.kt:121）✅；用户 disconnect → `markSessionInactive()` **在 executor.disconnect 之前**（:153）✅；意外断开（interpretShellResult IOException 清理）**不动** sessionActive（TuningExecutor 清理分支只动 connection/_connectionState）✅——三态语义与 PLAN 完全一致。
- **服务侧自愈不碰无线调试开关**：`ensureServiceConnection` 只调 `TuningExecutor.ensureConnected`（裸连接），**不调** `ensureWifiAdbThenConnect`（那个才会 enableWifiAdb）✅——「自动开启无线调试」权限收归用户显式 connect 流程，断开意图不被逆转。意外断开后 adbd 若也被关（用户系统设置关的）→ ensureConnected 拒连失败 → 服务本次 no-op，等下次显式连接——语义正确 ✅。
- 事件入口 sessionActive 检查（:83）且注释明确「lastApplied 不更新」✅。
- BootReceiver 独立会话：只调 ensureConnected + 注释「不 markSessionActive」✅ 开机恢复不激活服务会话。
- `connectionState` StateFlow 同步 UI（O10 一并落地）：VM init collect，服务侧重连 → UI「已连接（由自动应用恢复）」；意外断开 → UI 复位并提示 autoApply 将自愈 ✅。VM 重建（旋转/配置变更）场景：UiState 默认 false 而 executor 实态 true → collect 自动纠正——顺带修好了旧的状态脱节 ✅。

### 专项④ 更新链 BUG-021/022/023/024 — **PASS**

**021 host 白名单**：三域（github.com / objects.githubusercontent.com / release-assets.githubusercontent.com——覆盖 2024+ 新资产分发域）✅。**子域伪装审查**：`URL(url).host in ALLOWED_HOSTS` 是精确字符串匹配——`github.com.evil.io` 的 host 是整个字符串，不在集合内 ✅（UpdateSafetyTest 已覆盖该用例）；java.net.URL 对 host 做小写归一，大小写绕过不可行 ✅。**大小上限三重**：ReleaseInfo.size 预检 + Content-Length 检查 + **流式累计硬闸**（防 chunked 伪造绕过）✅，超限中止且 catch 分支 `dest.delete()` ✅。
- 小观察（不阻断）：白名单只校验 host 未校验 scheme——`http://github.com/...` 理论上会被放行（host 合法）。实际 GitHub 的 http 会 301 到 https，且内容仍受四重签名校验保护，风险极低。建议 round-4 顺手补 `url.startsWith("https://")`。

**022 tag 净化**：`TAG_SAFE = ^[A-Za-z0-9._-]+$` 字符集不含 `/` 与 `\`——穿越在字符层即被挡 ✅；裸 `.`/`..` 黑名单是语义防御（该形态文件名其实无害，黑名单消除歧义）✅；**双防线**：checkLatest 入口 check（恶意 tag 不进任何文件操作）+ updateApk 二道 check ✅。UpdateSafetyTest 穿越用例（`../../x`、`a/../../b`、`a/b`、`..`、`a\b`、`a b`、`a;b`、空串）全拒 ✅。

**023 线程切换完整性**：verifyApk/install 均改 `suspend + withContext(Dispatchers.IO)` ✅；调用方 VM 的 `_state.value = ...` 更新在挂起返回后回到 Main（StateFlow 本身也线程安全）✅；StrictMode 违规消除。

**024 abandon 路径**：try/catch(t) { `runCatching { session.abandon() }`; throw t } ✅——abandon 失败不掩盖原异常；commit 成功后异常仅影响状态展示（PLAN 已知风险，receiver 回终态兜底）✅；abandon 触发 receiver STATUS_FAILURE → 与 VM onFailure 双写状态，内容近似后写覆盖，无害（PLAN 已论证）✅。VM 侧 `install(ctx, f).onFailure { f.delete(); 状态 }` ✅ 崩溃路径彻底消除（四环全 Result 风格核对无遗漏）。

### 专项⑤ Wang 的 2 处偏差 — **均合理**

1. **isTagSafe 裸点黑名单**（9004c09）：PLAN 只要求字符集白名单，Wang 额外拒绝 `.`/`..`。字符集已挡穿越，黑名单属防御性收紧（语义异常 tag 直接拒），无副作用、有测试覆盖。**合理，偏保守方向，接受。**
2. **O3 `remember(ctx)` 带 key**（05afaf9）：PLAN 建议「简单 remember 即可」，Wang 用 ctx 作 key——Activity 重建时用新 context 重查一次（isAvailable 无状态，结果相同），代价一次 PM IPC，换取不持有旧 context 引用。**合理，比方案更稳，接受。**

（另核微差均等价无害：stillOurValues 签名省略 ctx 参数用 applicationContext；IntentFoldTest 选 PLAN 给出的「直接构造 Channel」选项而非抽 foldPending 纯函数；lastFailureWasRefused 用 private var + 读函数替代 PLAN 的 private set var。）

## 其余 BUG 逐项

- **BUG-025 PASS**：disableA11yService 回读对称探测（确认 flat 已移除）+ enable/disable 四处 putString 返回值显式检查（静默失败返回 false 不抛异常的场景被拦截）✅；VM 停用分支四态文案写进 adbStatus（与启用分支对称）✅。
- **BUG-026 PASS**：总闸 `withTimeout(60s)` 包裹整个 ensureWifiAdbThenConnect ✅；分类短路——`lastErrorIsRefused()`（ConnectException 才 true）非拒连失败（AUTH 等待超时/链路异常）立即给出「请在头显上点击允许」指引，不再 4 轮×35s ✅；分类失败默认可重试（不劣化现状）✅；connecting 态按钮变「取消」（connectJob.cancel）✅。取消后旧阻塞读线程最多残留一个 soTimeout 周期（round-1 已知边界，adbMutex 保证无协议损坏）——可接受。
- **BUG-028 PASS**：`scope?.cancel()` 先于新 scope 赋值 + eventQueue close 重建 ✅；cancel 不等待在途命令跑完（幂等 setprop 无害，PLAN 已论证）✅。
- **O1 PASS**：5s 窗口 + 200ms→1s 指数退避 ✅。
- **O2 PASS**：BootReceiver 自建 30s 轮询（3s 间隔）只调 ensureConnected，不嵌套 60s 总闸（boot 场景 adbd 慢启动）✅，A5 语义不变。
- **O4 PASS（附观察）**：appsLoading 防重入 + loading 态 UI + 按钮禁用 ✅。**小观察**：PLAN 明确要求「finally 置 false」，实现未用 try/finally 包裹——若 `AppScanner.query` 抛异常 appsLoading 将永久卡 true。round-1 BUG-010 修复后 query 内各步已有 runCatching 保护，实际抛异常概率极低，但按 PLAN 属实现偏差，建议顺手补 finally（一行）。
- **O5 PASS**：RFC1123 近似正则 + trim，非法输入走 Rejected 文案路径 ✅。
- **O6 PASS**：按方案文档化（pre-release 不触发自更新的安全方向注释），完整 semver 留 round-4 ✅。
- **O7/O8 PASS**：updateChecking 防重入（VM 守卫 + 按钮 enabled）✅；文案直读 ✅。
- **O9 不做**：理由成立——单 worker + 折叠后原建议针对的协程堆积场景已不存在 ✅。**O10 并入 BUG-027** ✅。

## 构建验证

```
命令：gradle.bat -p F:\workspace\QuestTuner clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
环境：JAVA_HOME/GRADLE_USER_HOME/TEMP,TMP 同前两轮
结果：BUILD SUCCESSFUL in 37s（92 tasks：48 executed, 41 from cache, 3 up-to-date）
单测：21/21（IntentFold 3 / MergeA11y 4 / ValuesMatch 5 / UpdateSafety 4 / VersionCompare 5）
```

## 实机验证建议清单（修复 FAIL 项后执行）

1. **019/020 主场景（最高优先）**：autoApply 开 + 应用 A 有 profile → 打开 A 1 秒内按 Home → getprop 恢复默认（当前版本会**残留**——FAIL 项实证点，修复后通过）；a11y.log 应有 "rolled back" 而非 "skip rollback"
2. 手动保护：服务应用 B → UI 手动 120Hz → Home → 120Hz 保留 + skip rollback 日志
3. 快速 A→B→A：最终参数 == A 的 profile（折叠保留最终态）
4. 027 三态：断开后切前台应用无重连、系统无线调试保持关；意外断开（重启 adbd）→ 切应用 → 自愈 + UI「已连接（由自动应用恢复）」
5. 026：未授权连接 35s 一次即出指引（非 140s）；「取消」按钮即时复位；拒连场景 4 次快速重试
6. 025：关 autoA11y → 系统无障碍列表同步移除 + 状态文案如实
7. 更新链：mock 白名单外 URL 拒绝；非法 tag 拒绝；StrictMode 干净；磁盘满不崩溃（updateStatus 显示安装失败）
8. 028：a11y 反复开关无协程泄漏
9. O 项抽查：选择器 loading 态、检查更新防重入、PrivateDns 非法主机名拦截

## 最终判定

**9/10 PASS、1/10 FAIL（BUG-020）。**
FAIL 根因在 PLAN 设计（Leave 意图未携带快照、stillOurValues 读已被置空的共享字段），Wang 实施与方案一致；修复量小（Leave 携带 AppliedRecord + 补时序单测），其余 9 项无需返工。**建议：Li 出一页补充方案 → Wang 修复 → 我做增量验证后合入。**

---

# Round3b 增量验证（快照修复复验）

- 验证人：David（QA）
- 日期：2026-10-24
- 对象：fix/round-3 追加 3 commits（799dfee / 0f5eded / da1fa94，9004c09 之后）
- 范围：仅增量验证（round-3 已 PASS 的 9 项不重验）；本章节结论覆盖上文「最终判定」中的 FAIL 项

## Round3b 结论

**BUG-020 复验：PASS。死代码链已根除，回滚路径恢复生效，且有编译期类型锁防回归。round-3 全分支（f5700e9..da1fa94，12 commits）达到合入标准。**

| 验证点 | 结论 |
|---|---|
| ① BUG-020 快照修复逐行核对 | **PASS** |
| ② LeaveSnapshotTest 类型锁有效性 | **PASS** |
| ③ scheme 校验无遗漏调用点 | **PASS** |
| ④ 三件套 24/24 | **PASS** |

### ① BUG-020 快照修复逐行核对 — PASS

commit 799dfee 对死代码链的三处根除（与 round-3 报告中的修复建议一致）：

1. **意图携带快照**：`Leave(val fromPkg: String)` → `Leave(val record: AppliedRecord)`（:37-38）——入队处 `q.trySend(ApplyIntent.Leave(prev))`（:102）携带完整 `AppliedRecord(pkg, profile, generation)` 快照 ✅
2. **比对用意图快照**：`stillOurValues()` 无参版（内含 `val rec = lastApplied ?: return false`）已删除，改为 `stillOurValues(record: AppliedRecord)`（:152），generation 与 valuesMatch 全部基于 `record` 参数 ✅——共享字段 `lastApplied` 不再参与回滚判定，**死亡路径（lastApplied 已置空 → 恒 false → 恒 skip → resetAll 永不执行）彻底根除**
3. **日志字段同步**：Leave 分支 `intent.fromPkg` → `intent.record.pkg`（:123, :126）✅

grep 全量核对 `stillOurValues|lastApplied`：`lastApplied` 剩余引用仅 5 处，全部在事件入口/Apply 失败复位（:91 防抖、:96 写入、:98-100 Leave 捕获、:115 失败复位），无任何路径再影响回滚判定 ✅。

**折叠序列下快照语义**：A→桌面 序列折叠后执行 Leave(recA)，recA 自持完整证据（pkg/profile/generation）；且比 round-3 共享字段方案**额外修复了竞态语义错乱**——此前 Leave 在队、后续 Apply(B) 覆盖 lastApplied 时会用 B 的记录回答 A 的回滚，现在结构上不可能发生 ✅。

### ② LeaveSnapshotTest 类型锁断言有效性 — PASS

三用例覆盖任务要求的全部维度：

1. `leaveCarriesSnapshot_notSharedField`：三层断言——①共享字段置空（assertNull，时序前提）②意图快照完整（pkg + refreshRate）③快照可完成 valuesMatch 比对（模拟 getprop 读回一致 → 回滚应发生）✅ 完整复现 round-3 FAIL 的时序并证明修复后行为正确。
2. `foldedSequence_applyLeaveApply_keepsFinalApply`：019 折叠 + 020 快照联合序列（A→桌面→A 保留最终 Apply）✅
3. `snapshotGenerationBlocksRollback`：代次快照忠实携带（供 stillOurValues 前置检查）✅

**类型锁验证（关键）**：`ApplyIntent.Leave(prev)` 要求 AppliedRecord 参数、`intent.record` 属性访问——两者在 round-3 旧代码（`Leave(fromPkg: String)`，无 record 属性）上都是**编译错误**。即：任何人回退修复都会在编译期被拦截，这是硬锁而非可绕过的行为断言 ✅。（generation 检查在 stillOurValues 内依赖 TuningExecutor 单例无法纯 JVM 测，测试注释如实说明该边界并改为验证快照忠实性——诚实且合理。）

小观察（不阻断）：测试通过复现事件入口的「捕获→置空→入队」代码模式模拟时序（onAccessibilityEvent 需 Android 框架，无法纯 JVM 调用）——类型锁 + 模式复现已是纯 JVM 单测的合理上限，实机清单第 1 条仍保留为最终确认。

### ③ scheme 校验无遗漏调用点 — PASS

commit 0f5eded：

- `checkHost` 升级为 `url.startsWith("https://") && URL(url).host in ALLOWED_HOSTS`（scheme + host 双检）✅
- `download` 入口校验从内联 host 检查改为 `check(checkHost(url))` 统一入口 ✅
- grep 全量核对 `URL(|openConnection|checkHost`：主代码网络出口仅 3 处——checkHost 自身、checkLatest 的 API 常量（编译期硬编码 `https://api.github.com`，不受外部输入污染，无需校验，合理）、download（已走 checkHost）——**无遗漏调用点** ✅
- UpdateSafetyTest 补 `http://github.com/...` 与 `ftp://github.com/...` 两例（白名单 host + 错误 scheme 均拒）✅

### ④ 三件套 24/24 — PASS

```
命令：clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
结果：BUILD SUCCESSFUL in 56s（92 tasks：46 executed, 43 from cache, 3 up-to-date）
单测：24/24（IntentFold 3 / LeaveSnapshot 3 / MergeA11y 4 / ValuesMatch 5 / UpdateSafety 4 / VersionCompare 5）
```

### 顺带项核对（0f5eded / da1fa94 内）

- **O4 finally 补齐**：loadApps 改 try/finally，finally 中复位 appsLoading（异常路径不再永久卡「加载中」）✅——补上了 round-3 报告的小观察；finally 在 IO 线程写 StateFlow（线程安全，注释说明）✅；异常时 apps 保持旧值、loading 复位，语义正确 ✅
- **da1fa94**：LeaveSnapshotTest 嵌套类引用修正（`ForegroundProfileService.AppliedRecord` 前缀）——internal 可见性 + 前缀引用是标准做法，无副作用 ✅

## Round3b 最终判定

**BUG-020 复验 PASS；round-3 全分支（f5700e9..da1fa94）10/10 全部通过，构建三件套全绿（24/24 单测）。建议合并到 master。**
实机清单以上文 round-3 章节为准（第 1 条「开应用 1 秒内 Home → 参数恢复默认；a11y.log 应有 rolled back 而非 skip rollback」为本次修复的最终实证点）。
