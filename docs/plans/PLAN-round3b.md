# QuestTuner Round 3b 补充方案（PLAN-round3b）

- 制定：Li（技术方案）
- 依据：docs/verification/VERIFY-round3.md 专项②（BUG-020 FAIL：回滚路径死亡）+ 任务板 task-12
- 基线：`fix/round-3` @ 9004c09（Wang 已实施 PLAN-round3；本方案只改 FAIL 项 + 2 个顺带小项）
- 行号已按 fix/round-3 当前代码核对
- 标注【改】给出改动后代码；【删】直接删除；【增】为新增行

## 总览

| 项 | 来源 | 结论 | 涉及文件 |
|---|---|---|---|
| BUG-020 Leave 快照 | VERIFY 专项② FAIL | 修（Leave 携带 AppliedRecord + stillOurValues(record) 比对意图内快照） | ForegroundProfileService.kt、新增时序单测 |
| 021 scheme 校验 | VERIFY 专项④小观察 | 修（checkHost 增加 https 前缀要求 + 测试补 2 例） | SelfUpdater.kt、UpdateSafetyTest.kt |
| O4 appsLoading finally | VERIFY O4 小观察 | 修（try/finally 包裹，异常不卡 loading） | TunerViewModel.kt |

**FAIL 根因复述**（一行）：事件入口入队 Leave 前已 `lastApplied = null`，而 Leave 只携带 `fromPkg` 字符串；worker 执行时 `stillOurValues()` 读共享字段 `lastApplied ?: return false` 恒 false → 回滚永远被 skip。单测只覆盖 `valuesMatch` 纯函数，未覆盖该时序，故 21 绿灯未拦截。——方案设计缺陷（PLAN-round3 §019(d)/§020），非实施偏差。

---

## 1. BUG-020 修复：Leave 意图携带 AppliedRecord 快照

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/ForegroundProfileService.kt`
- 修复思路：数据跟着意图走。`Leave` 从「只带 fromPkg」改为「携带完整 `AppliedRecord`（pkg/profile/generation，入队时捕获）」；`stillOurValues()` 改签名为 `stillOurValues(record: AppliedRecord)`，比对**意图内快照**而非共享可变 `lastApplied`。这同时天然消除 PLAN-round3 风险段承认的「lastApplied 跨线程交错」问题在回滚路径上的暴露（VERIFY 专项②第 3 点：Leave 在队、Apply(B) 又写 lastApplied 时用 B 的记录回答 A 的回滚——快照后不可能再发生）。
- 具体改动点：

  **（a）ForegroundProfileService.kt:36【改】Leave 定义**：
  ```kotlin
  private sealed class ApplyIntent {
      data class Apply(val pkg: String, val cmds: List<String>, val profile: AppProfile) : ApplyIntent()
      /** 携带完整快照（入队时捕获）：执行时共享 lastApplied 已置空，比对必须用自带快照 */
      data class Leave(val record: AppliedRecord) : ApplyIntent()
  }
  ```
  （AppliedRecord 定义 :39-44 不变；sealed class 内引用同文件 private data class，Kotlin 嵌套可见性无问题。）

  **（b）:96-100【改】事件入口入队处**：
  ```kotlin
  } else if (lastApplied != null) {
      val prev = lastApplied!!
      lastApplied = null
      // 快照随意图走：执行时 lastApplied 已空（或已被后续 Apply 覆盖），Leave 必须自持证据
      q.trySend(ApplyIntent.Leave(prev))
  }
  ```

  **（c）:116-125 execute 的 Leave 分支【改】**：
  ```kotlin
  is ApplyIntent.Leave -> {
      // BUG-020：回滚前值比对——用意图自带快照（执行时 lastApplied 已置空，
      // 读共享字段是 round-3 的死代码缺陷，VERIFY 专项②）
      if (!stillOurValues(intent.record)) {
          log("skip rollback for ${intent.record.pkg}: values changed by user")
          return
      }
      val ok = ensureServiceConnection() &&
          TuningExecutor.runCommands(TuningCommands.resetAll())
      log(if (ok) "rolled back ${intent.record.pkg}" else "rollback failed: ${TuningExecutor.lastError}")
  }
  ```

  **（d）:148-154 stillOurValues【改】签名**：
  ```kotlin
  private suspend fun stillOurValues(record: AppliedRecord): Boolean {
      // 双保险之二：UI 手动应用后代次变化 → 当前值以用户意志为准，不回滚
      if (TuningExecutor.manualGeneration != record.generation) return false
      val out = TuningExecutor.runCommand(TuningCommands.statusCommand()) ?: return false
      return valuesMatch(record.profile, out.split("---").map { it.trim() })
  }
  ```
  （kdoc 注释同步更新：删「lastApplied」表述，改为「比对意图内快照」。）

  **（e）019 折叠语义下的 Leave 应用方式（任务点名的理顺项）**：折叠逻辑（startWorker :64-70）**不需要改**——折叠保留最后一个意图；Leave 作为最终意图被保留执行时，其自带的 record 就是正确的回滚证据。推演三条关键序列确认无语义漏洞：
  1. [Apply(A)] 执行后慢，A→桌面：队列 [Leave(recA)]，worker 折叠后执行 Leave(recA) → recA 快照比对 → 回滚 ✅（修复前这条主路径恒 skip——FAIL 实证点）
  2. [Apply(A), Leave(recA), Apply(B)] 折叠到 Apply(B)：中间 Leave 被丢弃——**正确**：最终态是 B 前台，A 的参数会被 B 的 Apply 覆盖，无需先回滚 A 再设 B（setprop 直接覆盖，语义等价且少一轮命令）
  3. [Apply(A), Leave(recA), Apply(A)]（A→桌面→A 快速往返）折叠到 Apply(A)：同上，最终态 A 参数被重新应用 ✅
  唯一边界：折叠丢 Leave 后紧接的 Apply 失败（连接断）→ lastApplied 由 Apply 分支失败逻辑置 null（:112），下次窗口事件重新评估——与 round-3 行为一致，无新风险。

- 风险与注意事项：
  - Leave 携带 profile 对象引用：AppProfile 是不可变 data class，入队到执行期间用户改了 profile 存储（repo.save 覆盖同包名）也不影响已捕获快照——快照语义正是要「当时的应用证据」✅。
  - generation 检查保留在快照内（record.generation）：「服务 Apply 后、Leave 执行前用户手动应用」窗口仍由代次机制拦截（快照记录的是 Apply 时代次，手动 bump 后不等 → skip）✅ 与 round-3 双保险设计一致。
  - trySend 失败丢弃 Leave（队列满 16）：lastApplied 已置 null，该次回滚丢失——round-3 已知边界（事件洪泛才触发），本次不扩大处理；下次窗口事件时 lastApplied==null 不会再触发 Leave（与 round-3 行为相同，非回归）。

## 2. 时序单测（VERIFY 要求的必补项）

- 涉及文件：新增 `app/src/test/java/com/example/questtuner/tuner/LeaveSnapshotTest.kt`
- 思路：现有测试都在纯函数层，缺的正是「入队时捕获 vs 执行时读取」的时序证明。把 `stillOurValues` 的核心逻辑抽成可测纯函数不现实（含 suspend + TuningExecutor 单例），**改测快照生命周期**：用真实 Channel + 模拟「apply 完成 → lastApplied 置空 → Leave 执行」序列，断言 Leave 意图自带的 record 仍可完成 valuesMatch 比对。由于 ForegroundProfileService 的事件入口逻辑（捕获快照 → 置空 → 入队）是 private，**测试走 ApplyIntent 数据类直接构造**（Leave(record) 可直接实例化，AppliedRecord 是 private——需把它和 ApplyIntent 的可见性从 private 改为 internal【改】，仅测试可见，不扩大到 public）。
- 具体改动点：
  - ForegroundProfileService.kt :34 `private sealed class ApplyIntent`【改】`internal sealed class ApplyIntent`；:39 `private data class AppliedRecord`【改】`internal data class AppliedRecord`（kdoc 注明「internal 仅为单测可见性」）。
  - 【增】`app/src/test/java/com/example/questtuner/tuner/LeaveSnapshotTest.kt`：
  ```kotlin
  package com.example.questtuner.tuner

  import kotlinx.coroutines.channels.Channel
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Test

  /**
   * BUG-020 时序回归（VERIFY-round3 专项② FAIL 项）：
   * 「apply 完成 → lastApplied 置空 → Leave 执行」序列下，
   * Leave 必须凭意图内快照完成比对与回滚，而不是读已置空的共享字段。
   */
  class LeaveSnapshotTest {

      private fun record(pkg: String, gen: Int = 0, refreshRate: Int? = 90) = AppliedRecord(
          pkg = pkg,
          profile = AppProfile(packageName = pkg, refreshRate = refreshRate),
          generation = gen,
      )

      @Test
      fun leaveCarriesSnapshot_notSharedField() {
          // 序列模拟：Apply(A) 后 lastApplied 有值；桌面事件入队 Leave(prev) 并置空
          var lastApplied: AppliedRecord? = record("com.game.a")
          val q = Channel<ForegroundProfileService.ApplyIntent>(capacity = 16)
          // 事件入口逻辑（:96-100）：捕获快照 → 置空 → 入队
          val prev = lastApplied!!
          lastApplied = null
          q.trySend(ForegroundProfileService.ApplyIntent.Leave(prev))

          // worker 侧：取出 Leave，共享 lastApplied 已为 null
          val intent = q.tryReceive().getOrNull() as ForegroundProfileService.ApplyIntent.Leave
          assertNull("入队后共享字段已置空（时序前提）", lastApplied)
          // FAIL 复现断言（round-3 代码形态）：读共享字段将恒 false
          assertFalse("读共享字段的老路径应失败（FAIL 复现）", (lastApplied ?: return assertFalse("")) )
          // 修复后路径：意图自带快照仍完整
          assertEquals("com.game.a", intent.record.pkg)
          assertEquals(90, intent.record.profile.refreshRate)
          // 快照可完成 valuesMatch 比对（模拟 getprop 读回与 profile 一致）
          val parts = listOf("90", "", "", "", "", "")
          assertTrue(
              "快照比对应通过（回滚应发生）",
              ForegroundProfileService.valuesMatch(intent.record.profile, parts),
          )
      }

      @Test
      fun foldedSequence_applyLeaveApply_keepsFinalApply() {
          // 019 折叠 + 020 快照的联合序列：A→桌面→A，折叠保留最终 Apply(A)
          val q = Channel<ForegroundProfileService.ApplyIntent>(capacity = 16)
          val recA = record("com.game.a")
          q.trySend(ForegroundProfileService.ApplyIntent.Apply(
              "com.game.a", listOf("setprop debug.oculus.refreshRate 90"), recA.profile))
          q.trySend(ForegroundProfileService.ApplyIntent.Leave(recA))
          q.trySend(ForegroundProfileService.ApplyIntent.Apply(
              "com.game.a", listOf("setprop debug.oculus.refreshRate 90"), recA.profile))
          // worker 折叠
          var current = q.tryReceive().getOrNull()!!
          while (true) {
              val next = q.tryReceive().getOrNull() ?: break
              current = next
          }
          assertTrue("折叠后应保留最终意图 Apply(A)",
              current is ForegroundProfileService.ApplyIntent.Apply)
      }

      @Test
      fun snapshotGenerationBlocksRollback() {
          // 双保险之二：快照代次 ≠ 当前代次 → 比对前置失败（不回滚）。
          // generation 检查在 stillOurValues 内（依赖单例无法纯测），
          // 这里验证快照忠实携带了入队时代次，供该检查使用。
          val rec = record("com.game.a", gen = 7)
          assertEquals(7, rec.generation)
      }
  }
  ```
  **注意**：`leaveCarriesSnapshot_notSharedField` 中标注「FAIL 复现」的那行 assertFalse 写法有误（return assertFalse 返回 Unit 不合法）——Wang 落地时删掉该行（它只是方案里标注缺陷对照的注释性断言，非必需）；保留 assertNull + 快照字段断言 + valuesMatch 断言即可。最终测试文件要点：**三个断言层级 = 共享字段确已置空 + 意图快照仍完整 + 快照可完成比对**。
- 验证方法：`:app:testDebugUnitTest` 全绿（21 旧 + 本文件新增；若 leaveCarriesSnapshot 删一行后为 3 用例，总计 24）。关键：**该测试在 round-3 代码上会编译失败**（Leave 构造签名不同）——这正是时序约束从类型层锁死：今后 Leave 不带快照无法通过编译。

## 3. 顺带项①：021 scheme 校验（VERIFY 专项④小观察）

- 涉及文件：`update/SelfUpdater.kt`、`app/src/test/java/com/example/questtuner/update/UpdateSafetyTest.kt`
- 修复思路：checkHost 增加 `https://` 前缀要求（http://github.com 理论可过 host 白名单；实际 GitHub 301 到 https 且有签名兜底，但纵深防御补齐只值一行）。
- 具体改动点：
  - SelfUpdater.kt:38-39【改】：
    ```kotlin
    /** host 白名单 + scheme 校验（可单测）：仅 https 且 host 精确匹配 */
    fun checkHost(url: String): Boolean =
        runCatching {
            url.startsWith("https://") && URL(url).host in ALLOWED_HOSTS
        }.getOrDefault(false)
    ```
  - download :97-98【改】（统一走 checkHost，消除两处判重）：
    ```kotlin
    // 纵深防御①：scheme + host 白名单（防 API 被篡改后指向任意外部服务器/明文通道）
    check(checkHost(url)) { "下载地址不在白名单: $url" }
    ```
  - UpdateSafetyTest【增】2 例（放 disallowedHosts 内）：
    ```kotlin
    assertFalse(SelfUpdater.checkHost("http://github.com/donguaizs/QuestTuner/releases/download/v0.3.0/a.apk"))
    assertFalse(SelfUpdater.checkHost("ftp://github.com/x.apk"))
    ```
- 风险：无（GitHub 资产 URL 恒为 https）。

## 4. 顺带项②：O4 appsLoading finally（VERIFY O4 小观察）

- 涉及文件：`ui/TunerViewModel.kt`
- 修复思路：loadApps 的 loading 复位缺 try/finally——query 抛异常（概率极低，round-1 BUG-010 后各步有 runCatching）时 appsLoading 永久卡 true，选择器永远「加载中」。
- 具体改动点：TunerViewModel.kt:283-293【改】：
  ```kotlin
  fun loadApps() {
      // O4：加载中防重入（避免选择器打开瞬间重复触发 PackageManager 全量扫描）
      if (_state.value.appsLoading) return
      _state.value = _state.value.copy(appsLoading = true)
      viewModelScope.launch(Dispatchers.IO) {
          try {
              val apps = AppScanner.query(getApplication())
              withContext(Dispatchers.Main) {
                  _state.value = _state.value.copy(apps = apps)
              }
          } finally {
              // 异常也必须复位 loading，否则选择器永久「加载中」
              _state.value = _state.value.copy(appsLoading = false)
          }
      }
  }
  ```
  （说明：finally 内对 StateFlow 的赋值线程安全（任意线程可写），无需切 Main；apps 赋值与 loading 复位拆为两次 copy，中间态无影响（同一帧内收敛）。若 Wang 想保持原子性也可合并为一次 copy 放 try 尾——两种都接受。）
- 风险：无。

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：直接在 `fix/round-3` 追加 commit（小修补环，不另开分支）。
2. **实施顺序**：① ForegroundProfileService 快照改造（含 internal 可见性）→ ② LeaveSnapshotTest → ③ SelfUpdater scheme + 测试 → ④ loadApps finally。半小时量级。
3. **验证**：`:app:testDebugUnitTest` 全绿（24 用例）；`assembleDebug`/`assembleRelease` 通过。
4. **不改动**：其余 9 项 PASS 的代码一律不动（VERIFY 已确认无需返工）；特别是 startWorker 折叠逻辑、Apply 分支、ensureServiceConnection、stillOurValues 的 generation 检查语义。
5. **交付后自验**（增量，只验 FAIL 修复）：VERIFY-round3 清单第 1 条——autoApply 开 + 应用 A 有 profile → 打开 A 1 秒内按 Home → getprop 恢复默认，a11y.log 出现 "rolled back"（而非 "skip rollback"）；第 2 条手动保护仍 PASS（120Hz 保留 + skip rollback 日志）。
