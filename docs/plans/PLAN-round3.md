# QuestTuner Round 3 修复方案（PLAN-round3）

- 制定：Li（技术方案）
- 依据：docs/bugs/BUG-REPORT-round3.md（David，BUG-019~028 + 优化建议 O1-O10）
- 基线：master @ 933c515（round-2 已合入）；修复在 `fix/round-3` 分支进行
- 行号基于 933c515 核对；Wang 改动时如行号漂移请按代码片段定位
- 标注【改】给出改动后代码；【删】直接删除；【增】为新增行/文件
- 沿用 round-2 假设台账风格：平台行为依赖逐处标注「假设/探测/失败」

## 总览

| 编号 | 级别 | 结论 | 涉及文件 |
|---|---|---|---|
| BUG-019 | P1 | 修（单 worker Channel 串行队列） | ForegroundProfileService.kt |
| BUG-020 | P2 | 修（回滚前 getprop 值比对 + 手动应用通知服务） | ForegroundProfileService.kt、TuningExecutor.kt、TunerViewModel.kt |
| BUG-021 | P2 | 修（host 白名单 + 100MB 上限） | SelfUpdater.kt |
| BUG-022 | P2 | 修（tag 净化） | SelfUpdater.kt |
| BUG-023 | P2 | 修（verifyApk/install 移交 IO） | SelfUpdater.kt、TunerViewModel.kt |
| BUG-024 | P2 | 修（install runCatching + Result 返回） | SelfUpdater.kt、TunerViewModel.kt |
| BUG-025 | P2 | 修（disable 回读对称） | SecureAutomation.kt |
| BUG-026 | P2 | 修（总闸 60s + 取消按钮 + 重试分类短路） | SecureAutomation.kt、TuningExecutor.kt、TunerViewModel.kt、TunerApp.kt |
| BUG-027 | P2 | 修（adbSessionActive 会话标志 + 服务建连后 UI 同步） | TuningExecutor.kt、TunerViewModel.kt、ForegroundProfileService.kt、BootReceiver.kt |
| BUG-028 | P2 | 修（scope 覆盖前 cancel） | ForegroundProfileService.kt |
| O1-O10 | 优化 | 6 修 / 2 延后 / 2 不做 | 见 §优化建议 |

**建议实施顺序**：① 019+028+020（同文件 ForegroundProfileService，Channel 队列一次落地；020 依赖 019 的队列结构）→ ② 024+023（更新链异常/线程）→ ③ 021+022（更新链安全）→ ④ 027（会话语义，跨 3 文件）→ ⑤ 026（连接总闸）→ ⑥ 025（对称回读）→ ⑦ O1-O10 中本轮项。每步独立可编译。

---

## 假设台账（round-3 新增，沿用 A 编号接续 round-2 的 A1-A9）

| # | 假设 | 运行时探测方法 | 失败时行为 |
|---|---|---|---|
| A10 | `adb shell getprop <key>` 输出即为 setprop 写入的确切值（同值往返，无固件改写）——BUG-020 值比对的前提 | 比对逻辑本身就是探测：getprop 读回值 ≠ profile 期望值 → 视为「用户已手动改动」，跳过回滚 | 比对不一致 → 不回滚（保守方向：宁可残留也不误清用户设置） |
| A11 | `ConnectException`（TCP 拒绝）可与其他失败（AUTH 超时 IOException 等）区分——BUG-026 重试分类短路的前提 | `TuningExecutor.lastErrorIsRefused()`：connect 阶段捕获异常类型分类记录 | 分类失败（无法判断）→ 按可重试处理（行为同现状，不劣化） |
| A12 | 服务通过 `SessionEvent` 通知 VM 的跨组件通信在同一进程内可靠（无障碍服务与 Activity 同进程，manifest 未分 process） | `TuningExecutor.connectionState` StateFlow 直读（同进程单例，无需 IPC） | — （非平台行为，进程内事实） |

---

## BUG-019（P1）前台切换命令乱序：桌面态残留应用参数且无法自愈

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/ForegroundProfileService.kt`
- 方案评估（Channel 串行队列 vs 意图序号）：
  - **Channel 单 worker 串行队列（选定）**：`onAccessibilityEvent` 只做 `channel.trySend(意图)`，单个 worker 协程 `for (e in channel)` 顺序消费执行。FIFO 由 Channel 天然保证（Channel 内部无界/有界队列保序，单消费者消除并发）；事件入口不再 launch 多个协程，竞争 adbMutex 的主体只剩 UI 侧（互斥已由 round-1 保证）。
  - 意图序号（落选）：每命令携带序号、执行前校验最新意图——需要在执行前二次查询「最新序号」，而查询时新事件可能刚好入队，仍需与事件入口互斥；序号读写本身又引入新的共享状态。等于用手工方式重造 Channel 已有的 happens-before 保证，复杂度更高、出错面更大。
  - 另有第三方案「事件入口单 Mutex 串行化」：能保序但把执行时间（连接+命令，秒级）计入锁持有，阻塞事件分发线程（onAccessibilityEvent 在主线程），有 ANR 风险。落选。
- 修复思路：trySend 非阻塞（队列满/异常直接丢弃旧事件——丢的是过渡态，安全）；worker 消费时做**最新意图合并**：send 是即时的，执行是慢的，worker 每处理完一个意图后 `tryReceive` 看有没有更新意图，队列里有多个时只保留最后一个（中间态执行无意义，还拉长错序窗口）。这同时把 BUG-020 的「快速 A→B→桌面」压缩为只执行「桌面」意图。
- 具体改动点（整段重写 ForegroundProfileService 的事件处理，同时落地 BUG-028 修复）：

  **（a）import 区【增】**：
  ```kotlin
  import kotlinx.coroutines.channels.Channel
  import kotlinx.coroutines.isActive
  ```

  **（b）类成员（:18-23）【改】**：
  ```kotlin
  private var scope: CoroutineScope? = null
  private var repo: ProfileRepository? = null
  private var prefs: TunerPrefs? = null
  /** 意图队列：单 worker 串行消费，保证事件序 == 执行序（BUG-019） */
  private var eventQueue: Channel<ApplyIntent>? = null
  /** 上一次由本服务应用过 profile 的包及当时的 profile（BUG-020 值比对用） */
  private var lastApplied: AppliedRecord? = null

  /** 执行意图：进有 profile 应用=应用；离开=回滚（仅当值仍属我们） */
  private sealed class ApplyIntent {
      data class Apply(val pkg: String, val cmds: List<String>, val profile: AppProfile) : ApplyIntent()
      data class Leave(val fromPkg: String) : ApplyIntent()
  }
  private data class AppliedRecord(val pkg: String, val profile: AppProfile)
  ```

  **（c）onServiceConnected（:25-31）【改】**（含 BUG-028）：
  ```kotlin
  override fun onServiceConnected() {
      super.onServiceConnected()
      // BUG-028：系统对同一实例重复回调时，旧 scope 先 cancel 再覆盖
      scope?.cancel()
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      repo = ProfileRepository.of(applicationContext.filesDir)
      prefs = TunerPrefs.get(applicationContext)
      eventQueue?.close()
      eventQueue = Channel(capacity = 16)
      startWorker()
      log("service connected")
  }

  /** 单 worker：FIFO 消费意图；执行前合并队列中已排队的更新意图（只留最新） */
  private fun startWorker() {
      val s = scope ?: return
      val q = eventQueue ?: return
      s.launch {
          for (intent in q) {
              // 合并：处理慢（连接+命令秒级），把队列里积压的中间意图折叠成最后一个
              var current = intent
              while (true) {
                  val next = q.tryReceive().getOrNull() ?: break
                  current = next   // 丢弃中间态，仅保留最新意图
              }
              execute(current)
          }
      }
  }
  ```

  **（d）onAccessibilityEvent（:33-54）【改】**：
  ```kotlin
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
      if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
      if (prefs?.autoApplyProfile != true) return
      if (!TuningExecutor.adbSessionActive) return   // BUG-027：用户断开后服务 no-op（见该节）
      val pkg = event.packageName?.toString() ?: return
      if (pkg == applicationContext.packageName) return
      val r = repo ?: return
      val q = eventQueue ?: return
      // 防抖：包级去重（同包不重复入队）
      if (pkg == lastApplied?.pkg) return

      val profile = r.find(pkg)
      if (profile != null) {
          lastApplied = AppliedRecord(pkg, profile)   // 记录值快照，回滚比对用（BUG-020）
          q.trySend(ApplyIntent.Apply(pkg, profile.toCommands(), profile))
      } else if (lastApplied != null) {
          val prev = lastApplied!!
          lastApplied = null
          q.trySend(ApplyIntent.Leave(prev.pkg))
      }
      // trySend 失败（队列满 16）= 系统事件洪泛，丢弃旧意图安全（最新意图会在队列腾空后到达）
  }
  ```

  **（e）执行函数（:56-67 applyOrRetry 【改】重写为 execute + 回滚比对，含 BUG-020）**：
  ```kotlin
  private suspend fun execute(intent: ApplyIntent) {
      val ctx = applicationContext
      when (intent) {
          is ApplyIntent.Apply -> {
              val ok = TuningExecutor.runCommands(intent.cmds)
              if (ok) {
                  log("applied ${intent.pkg} (${intent.cmds.size} cmds)")
              } else {
                  // 失败允许下次前台切换重试
                  if (lastApplied?.pkg == intent.pkg) lastApplied = null
                  log("apply failed for ${intent.pkg}: ${TuningExecutor.lastError}")
              }
          }
          is ApplyIntent.Leave -> {
              // BUG-020：回滚前值比对——当前属性值已不是我们应用过的（用户手动改过）→ 不动
              if (!stillOurValues(ctx)) {
                  log("skip rollback for ${intent.fromPkg}: values changed by user")
                  return
              }
              val ok = TuningExecutor.runCommands(TuningCommands.resetAll())
              log(if (ok) "rolled back ${intent.fromPkg}" else "rollback failed: ${TuningExecutor.lastError}")
          }
      }
  }

  /**
   * BUG-020 值比对：getprop 读当前值，与我们应用过的 profile 期望值逐项对比。
   * 假设A10：getprop 输出 == setprop 写入的确切值（同值往返）。
   * 探测：比对本身；任一项不匹配 → 视为用户手动改动，返回 false。
   * 失败（getprop 读不到/连接断）：返回 true（保守回滚——沿用旧行为）还是 false（保守保留）？
   * 选 false：读不到就无法证明「值仍属我们」，宁可不回滚也不误清；残留参数由用户下次
   * 手动恢复默认或进入有 profile 应用覆盖，可自愈且不违背「不清用户手动设置」承诺。
   */
  private suspend fun stillOurValues(ctx: android.content.Context): Boolean {
      val rec = lastApplied ?: return false
      val out = TuningExecutor.runCommand(TuningCommands.statusCommand()) ?: return false
      // statusCommand 输出按 "; echo ---; " 分段，与 TuningCommands.kt:62-69 的属性序一致：
      // [0]=refreshRate [1]=textureWidth [2]=textureHeight [3]=cpuLevel [4]=gpuLevel [5]=highPrecisionPassthrough
      val parts = out.split("---").map { it.trim() }
      if (parts.size < 6) return false
      val p = rec.profile
      // 只比对 profile 中设置过的项；未设置的项当时应为空（setprop 前后一致即可确认属我们）
      p.refreshRate?.let { if (parts[0] != it.toString()) return false }
      p.textureWidth?.let { if (parts[1] != it.toString()) return false }
      p.textureHeight?.let { if (parts[2] != it.toString()) return false }
      p.cpuLevel?.let { if (parts[3] != it.toString()) return false }
      p.gpuLevel?.let { if (parts[4] != it.toString()) return false }
      return true
  }
  ```

  **（f）onDestroy（:71-75）【改】**：
  ```kotlin
  override fun onDestroy() {
      eventQueue?.close()
      eventQueue = null
      scope?.cancel()
      scope = null
      super.onDestroy()
  }
  ```

  **（g）连接职责变化说明**：execute 内不再调用 `ensureWifiAdbThenConnect`（原 applyOrRetry:58 有）——连接职责移交给 BUG-027 的会话机制（服务仅在 `adbSessionActive` 时工作，连接由 UI connect 建立；详见 BUG-027 节）。这同时消除「服务每次应用都可能重开无线调试」的逆转问题。

- 风险与注意事项：
  - Channel capacity=16 + tryReceive 合并：正常前台切换频率远低于 16；事件洪泛时丢弃中间态正是期望行为。
  - worker 挂了（execute 抛非预期异常）：`for (intent in q)` 循环体异常会终止 worker → 后续事件全部静默失效。**防御**：execute 外包 runCatching（execute 内部逻辑已全为 Result 风格，此处是最后防线）：
    ```kotlin
    runCatching { execute(current) }.onFailure { log("worker error: ${it.message}") }
    ```
  - lastApplied 从 `String?` 变为 `AppliedRecord?`：字段的读写在事件入口（主线程）与 worker（IO）并发——现状 round-2 亦如此（非 volatile 可变字段跨线程），风险窗口为「失败后置 null 与新事件写值的交错」。Channel 引入后事件入口只写、worker 只读回滚判断，交错影响限于极端时序下多执行/少执行一次幂等 setprop，无害。不额外加锁（避免复杂化），代码注释说明。
  - 服务端 execute 与 UI applyProfile 并发：adbMutex 互斥已保证；乱序问题只存在于服务内部多协程（本修复消除）。
- 验证方法：
  1. 单测（纯 JVM 可测部分）：`ApplyIntent` 合并逻辑抽出为内部函数 `foldPending(queue)` 单测——入队 A/B/Leave 三意图，断言 worker 只执行 Leave；或直接构造 Channel 验证 tryReceive 折叠行为。
  2. 伪 shell 集成测试：stub TuningExecutor.runCommands 记录调用序，模拟「Apply(A)→Leave」快速两事件，断言执行序 == 事件序（修复前必现乱序窗口）。
  3. 实机：autoApply 开 + 应用 A 有 profile → 冷启动 A 并 1 秒内按 Home → getprop refreshRate 恢复默认（修复前残留 profile 值且不自愈）。

---

## BUG-020（P2）autoApply 的 resetAll 清掉用户手动设置的参数

- 涉及文件：`ForegroundProfileService.kt`（主）、`TuningExecutor.kt`、`ui/TunerViewModel.kt`
- 修复思路（双保险）：
  1. **回滚前值比对**（主修复，随 BUG-019 落地）：Leave 意图执行 resetAll 前 `stillOurValues()` getprop 比对——当前值与「我们应用过的 profile 期望值」逐项核对，任一不匹配即用户手动改过 → 跳过回滚。
  2. **手动应用通知服务**（辅助）：UI 手动 applyProfile/QuickTune/resetAll 后，服务的 lastApplied 失效——手动应用意味着「当前值以用户意志为准」，服务后续 Leave 不应回滚。经 TuningExecutor 单例加一个轻量代次计数器实现（服务与 VM 同进程，A12）。
- 具体改动点：

  **（a）TuningExecutor.kt【增】**（类成员，:20 附近）：
  ```kotlin
  /** 手动应用代次：UI 每次手动应用/恢复默认时递增；服务比对快照代次判断是否已被手动覆盖 */
  @Volatile var manualGeneration: Int = 0
      private set

  fun bumpManualGeneration() { manualGeneration++ }
  ```
  **（b）TunerViewModel.kt【增】**：applyProfile（:249 内 runCommands 前）、resetAll（:292 内 runCommands 前）各加一行：
  ```kotlin
  TuningExecutor.bumpManualGeneration()
  ```
  **（c）ForegroundProfileService.kt**：AppliedRecord【改】增加代次字段，Apply 入队时记录，Leave 比对时校验：
  ```kotlin
  private data class AppliedRecord(val pkg: String, val profile: AppProfile, val generation: Int)
  // 入队处：
  lastApplied = AppliedRecord(pkg, profile, TuningExecutor.manualGeneration)
  // stillOurValues() 开头：
  val rec = lastApplied ?: return false
  if (TuningExecutor.manualGeneration != rec.generation) return false   // 手动覆盖过 → 不回滚
  ```
- 风险与注意事项：
  - A10 假设（getprop 同值往返）若不成立（固件改写值）：比对失败 → 跳过回滚 → 残留参数。保守方向正确（宁残留不误清）；文案/UI 无需变化（a11y.log 有 skip 记录可排查）。
  - 值比对只覆盖 profile 设置过的项：profile 未设置的项（如用户手动只改了 cpuLevel 而 profile 只含 refreshRate）——getprop parts[3] 比对不在 p.cpuLevel 分支内，不会比对 → 该场景下手动 cpuLevel 仍会被 resetAll 清掉。**权衡**：把「未设置项也要求为空」会误杀「用户在我们应用前就有手动值」的场景（服务 apply 时未触碰该项，回滚却清它——同样违背承诺）。两害相权：接受「profile 未覆盖项的手动值仍可能被清」，注释说明；彻底解法是 per-property 回滚快照（记录 apply 前的旧值，Leave 时还原旧值而非 resetAll）——**作为可选增强留给 Wang 余量**（改动集中在 execute 的 Apply 分支记录快照 + Leave 分支还原，方案不强制）。
  - manualGeneration 溢出：Int 每次手动应用 +1，实际永不溢出。
- 验证方法：
  1. 单测：stillOurValues 的比对表（profile 值匹配/不匹配/分段不足/generation 不等）——比对逻辑抽出为纯函数 `valuesMatch(profile: AppProfile, parts: List<String>): Boolean` 便于测试。
  2. 实机：autoApply 开 + 应用 B 有 profile → 打开 B → 回 UI 手动「全局 120Hz」→ Home → getprop refreshRate == 120（修复前被清空）。a11y.log 出现 skip rollback 记录。

---

## BUG-021（P2）自更新下载缺纵深防御：URL host 无白名单 + 无大小上限

- 涉及文件：`app/src/main/java/com/example/questtuner/update/SelfUpdater.kt`
- 修复思路：download 前校验 URL host ∈ 白名单（github.com / objects.githubusercontent.com / release-assets.githubusercontent.com——GitHub Releases 资产的实际分发域，2024+ 新资产走 release-assets 子域）；下载循环内强制累计大小上限（API 给的 `ReleaseInfo.size` 与硬上限 100MB 双重：size 已知超限直接拒绝，流式累计超限中止删文件——防 contentLength 伪造）。
- 具体改动点：

  **（a）SelfUpdater.kt【增】白名单常量（:20 附近）**：
  ```kotlin
  /** 允许的下载 host（GitHub Releases 资产分发域）；API 响应中的其他 host 一律拒绝 */
  private val ALLOWED_HOSTS = setOf(
      "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
  )
  /** 下载硬上限：正常 APK <10MB 量级，100MB 是安全余量上限 */
  private const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024
  ```

  **（b）download()（:68-102）【改】**——入口加 host 校验、循环加累计上限：
  ```kotlin
  suspend fun download(
      url: String,
      expectedSize: Long,          //【增】ReleaseInfo.size；<=0 表示未知
      dest: File,
      onProgress: (Int) -> Unit,
  ): Result<File> = withContext(Dispatchers.IO) {
      runCatching {
          // 纵深防御①：host 白名单（防 API 被篡改后指向任意外部服务器）
          val host = URL(url).host
          check(host in ALLOWED_HOSTS) { "下载地址不在白名单: $host" }
          // 纵深防御②：已知 size 预检
          if (expectedSize > MAX_DOWNLOAD_BYTES) error("文件过大: ${expectedSize / 1024 / 1024}MB")
          dest.parentFile?.mkdirs()
          val conn = URL(url).openConnection() as HttpURLConnection
          conn.connectTimeout = 15_000
          conn.readTimeout = 60_000
          try {
              if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
              val total = conn.contentLengthLong
              // contentLength 可被伪造/缺失：流式累计才是硬闸
              if (total > MAX_DOWNLOAD_BYTES) error("Content-Length 超限")
              dest.outputStream().use { out ->
                  conn.inputStream.use { input ->
                      val buf = ByteArray(16 * 1024)
                      var done = 0L
                      while (true) {
                          val n = input.read(buf)
                          if (n < 0) break
                          done += n
                          // 纵深防御③：累计硬上限（防 chunked 传输绕过 contentLength）
                          if (done > MAX_DOWNLOAD_BYTES) error("下载超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB 上限，中止")
                          out.write(buf, 0, n)
                          if (total > 0) onProgress((done * 100 / total).toInt())
                      }
                  }
              }
              dest
          } catch (t: Throwable) {
              dest.delete()
              throw t
          } finally {
              conn.disconnect()
          }
      }
  }
  ```

  **（c）调用方 TunerViewModel.downloadAndInstall（:345）【改】**：
  ```kotlin
  SelfUpdater.download(info.url, info.size, apk) { p -> ... }
  ```

- 风险与注意事项：
  - 白名单过严风险：GitHub 未来更换分发域 → 下载被拒（错误信息明确列出 host）→ 用户可暂用 PC 更新。安全优先于便利，且错误可诊断。
  - 301/302 重定向：HttpURLConnection 默认跟随重定向，若重定向链跳出白名单域——**跟随后的最终 URL 不再校验**（连接已建立）。纵深做法是 `conn.instanceFollowRedirects = false` 手动逐跳校验；**权衡**：GitHub 资产重定向链（github.com → objects/release-assets.githubusercontent.com）都在白名单内，逐跳校验增加约 20 行复杂度。**本方案选做**：Wang 余量充足时实现逐跳（最多 5 跳，每跳校验 host）；余量不足时保持现状并在注释标注「重定向终点未校验，信任 HttpURLConnection 跟随链」——签名校验仍是内容级兜底。
  - 100MB 上限偏大（实际 APK 数 MB）：防御目标是「写满存储的恶意流」，100MB 足以拦截，又不会误伤未来带资源的版本。
- 验证方法：
  1. 单测：host 校验纯函数化（`checkHost(url): Boolean`）覆盖白名单内/外/无 host URL；构造小上限 + 长 stream stub 断言中止删文件。
  2. 交付后自验：mock 或 hosts 文件把 API 响应指向局域网 → 「下载地址不在白名单」错误显示，不发起连接。

---

## BUG-022（P2）updateApk 用 GitHub tag 拼文件路径 → 路径穿越

- 涉及文件：`app/src/main/java/com/example/questtuner/update/SelfUpdater.kt`
- 修复思路：tag 只允许 `[A-Za-z0-9._-]`（覆盖 vX.Y.Z 常规命名），含 `/`、`..`、`\` 等一律拒绝（check 失败 → checkLatest 阶段就报错，不让恶意 tag 进入任何文件操作）。
- 具体改动点：

  **（a）SelfUpdater.kt:104-105【改】**：
  ```kotlin
  private val TAG_SAFE = Regex("^[A-Za-z0-9._-]+$")

  fun updateApk(context: Context, tag: String): File {
      // 净化：git tag 允许含 '/'，可构造路径穿越；仅放行安全字符集
      check(TAG_SAFE.matches(tag)) { "非法 tag: $tag" }
      return File(File(context.getExternalFilesDir(null), "update"), "questtuner-$tag.apk")
  }
  ```

  **（b）防御前移（推荐，checkLatest :45-46 处【增】）**：tag 从 API JSON 读出时即校验，非法 tag 在检查阶段报错：
  ```kotlin
  val tag = o.optString("tag_name")
  if (tag.isBlank()) return@runCatching null
  check(TAG_SAFE.matches(tag)) { "Release tag 含非法字符: $tag" }
  ```

- 风险与注意事项：无。合法 tag 命名（v0.3.0、0.3.0-beta.1）全部匹配；校验失败路径是 error → checkLatest onFailure → UI 显示原因，行为清晰。
- 验证方法：单测：tag = `"../../x"`、`"a/../../b"`、`"v0.3.0"`、`"0.3.0-beta.1"` 断言 updateApk 抛/不抛，且合法 tag 的 canonicalPath 仍在 update/ 下。

---

## BUG-023（P2）verifyApk / install 在主线程执行（StrictMode 违规）

- 涉及文件：`update/SelfUpdater.kt`、`ui/TunerViewModel.kt`
- 修复思路：两层修复——SelfUpdater 函数自身声明为 suspend + withContext(IO)（治本：任何调用方都不再踩坑）；调用方 onSuccess 回调自然落在 IO 上下文。与 BUG-024 一并改（同函数签名）。
- 具体改动点：

  **（a）SelfUpdater.verifyApk（:108-123）【改】签名**：
  ```kotlin
  suspend fun verifyApk(context: Context, f: File): Result<Unit> =
      withContext(Dispatchers.IO) {
          runCatching { /* ……原 :109-122 校验逻辑不变…… */ }
      }
  ```

  **（b）SelfUpdater.install（:141-159）【改】签名**（与 BUG-024 合并）：
  ```kotlin
  suspend fun install(context: Context, apk: File): Result<Unit> =
      withContext(Dispatchers.IO) {
          runCatching {
              val installer = context.packageManager.packageInstaller
              val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
              val session = installer.openSession(installer.createSession(params))
              session.openWrite("questtuner", 0, -1).use { out ->
                  apk.inputStream().use { it.copyTo(out) }
                  out.flush()
              }
              val intent = Intent(context, InstallResultReceiver::class.java)
                  .putExtra("apk_path", apk.absolutePath)
              val pending = android.app.PendingIntent.getBroadcast(
                  context, 1001, intent,
                  android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
              )
              session.commit(pending.intentSender)
              session.close()
          }
      }
  ```
  （install 的 openSession 失败时 session 未创建无泄漏；openWrite 失败时 session 需 abandon——补强：`runCatching` 内 `session.close()` 不够干净，改为：
  ```kotlin
  val session = installer.openSession(installer.createSession(params))
  try {
      session.openWrite("questtuner", 0, -1).use { out ->
          apk.inputStream().use { it.copyTo(out) }
          out.flush()
      }
      // ……pendingIntent 构造与 commit……
  } catch (t: Throwable) {
      session.abandon()
      throw t
  }
  session.close()
  ```
  注意 abandon 的 receiver 同样会收到 STATUS_FAILURE（走 InstallResultReceiver 失败分支），提示文案正确。）

  **（c）TunerViewModel.downloadAndInstall（:347-357）【改】**：install 现在返回 Result，调用处更新：
  ```kotlin
  }.onSuccess { f ->
      SelfUpdater.verifyApk(ctx, f).onSuccess {
          _state.value = _state.value.copy(
              updateStatus = "校验通过，等待系统确认…", updateProgress = -1)
          SelfUpdater.install(ctx, f).onFailure { e ->
              // BUG-024：安装异常（磁盘满/会话失败）→ 状态提示，不再有崩溃路径
              f.delete()
              _state.value = _state.value.copy(
                  updateStatus = "安装失败: ${e.message}（可用 PC adb install 手动更新）",
                  updateProgress = -1)
          }
      }.onFailure { e ->
          f.delete()
          _state.value = _state.value.copy(
              updateStatus = "校验失败，已取消: ${e.message}", updateProgress = -1)
      }
  }
  ```
  （状态更新 `_state.value = ...` 在 IO 线程调用——StateFlow 线程安全，可从任意线程更新，无问题。）

- 风险与注意事项：
  - verifyApk/install 改 suspend 后，若其他调用方存在（当前仅 TunerViewModel 一处）需同步适配——grep 确认。
  - StateFlow 跨线程写：`_state.value =` 赋值原子且线程安全；UI 收集在 Main——行为不变。
- 验证方法：StrictMode penaltyLog 走完整更新流程（检查→下载→校验→安装），logcat 无 DiskReadViolation（修复前 verifyApk/install 处出现）；模拟外部存储只读（chmod 或 stub）→ updateStatus 显示失败而非 crash（同时覆盖 BUG-024）。

---

## BUG-024（P2）install() 无异常保护，磁盘满等 IOException 直接崩溃

- 涉及文件：`update/SelfUpdater.kt`、`ui/TunerViewModel.kt`
- 修复思路：install 整体 runCatching 返回 Result（见 BUG-023 (b)——两项同一次改动完成：suspend + IO + Result + session abandon）；调用方 onFailure 更新状态文案（见 BUG-023 (c)）。崩溃路径彻底消除：checkLatest/download/verifyApk/install 四环全部 Result 风格。
- 风险与注意事项：
  - commit 成功后 session.close() 的 close 本身也可能抛（罕见）——包在 runCatching 内，commit 已成功此时异常仅影响状态展示（receiver 会正常回终态），可接受。
  - abandon 路径 receiver 收到 STATUS_FAILURE → notifyInstallResult(false) → UI 状态与 onFailure 分支可能双写（后写覆盖先写，内容近似）——无害。
- 验证方法：磁盘满模拟（向 getExternalFilesDir 灌满或 stub outputStream 抛 IOException）→ 不崩溃、updateStatus 显示「安装失败」。单测：stub PackageInstaller 使 openWrite 抛 → install 返回 failure、session abandon 被调用。

---

## BUG-025（P2）disableA11yService 写入后无回读验证（探测纪律不对称）

- 涉及文件：`app/src/main/java/com/example/questtuner/tuner/SecureAutomation.kt`
- 修复思路：与 enable 分支对称——写入 rest 后回读确认 flat 已不在列表；同时 putString 返回值本身要检查（putString 静默失败返回 false 不抛异常）。
- 具体改动点：SecureAutomation.kt:100-112【改】：
  ```kotlin
  fun disableA11yService(context: Context): CapProbe {
      if (!hasWriteSecureSettings(context)) return CapProbe.NoPermission
      return runCatching {
          val cr = context.contentResolver
          val flat = a11yComponentName(context).flattenToString()
          val cur = Settings.Secure.getString(cr, "enabled_accessibility_services") ?: ""
          val rest = cur.split(':').map { it.trim() }
              .filter { it.isNotEmpty() && it != flat }.joinToString(":")
          // putString 返回 false = 静默失败（不抛异常）——显式检查
          if (!Settings.Secure.putString(cr, "enabled_accessibility_services", rest)) {
              return@runCatching CapProbe.WriteRejected
          }
          if (rest.isEmpty() && !Settings.Secure.putString(cr, "accessibility_enabled", "0")) {
              return@runCatching CapProbe.WriteRejected
          }
          // 回读探测（与 enable 分支 :94-96 对称）：确认 flat 已移除
          val stored = Settings.Secure.getString(cr, "enabled_accessibility_services") ?: ""
          if (stored.split(':').map { it.trim() }.contains(flat)) CapProbe.WriteRejected
          else CapProbe.Ok
      }.getOrElse { CapProbe.Error(it.message) }
  }
  ```
  **顺带对称补强（enableA11yService :90-92）**：两处 putString 的返回值同样检查：
  ```kotlin
  if (!Settings.Secure.putString(cr, "enabled_accessibility_services", next)) return@runCatching CapProbe.WriteRejected
  if (!Settings.Secure.putString(cr, "accessibility_enabled", "1")) return@runCatching CapProbe.WriteRejected
  ```
- 风险与注意事项：无。回读失败按 WriteRejected 上报，调用方（setAutoA11y false 分支）目前忽略返回值——**顺带改**：TunerViewModel.kt:160-165 的 disable 分支把结果写进 adbStatus（与 enable 分支文案对称）：
  ```kotlin
  viewModelScope.launch(Dispatchers.IO) {
      val r = SecureAutomation.disableA11yService(getApplication())
      withContext(Dispatchers.Main) {
          _state.value = _state.value.copy(
              a11yRunning = false,
              adbStatus = when (r) {
                  is SecureAutomation.CapProbe.Ok -> "无障碍服务已停用"
                  is SecureAutomation.CapProbe.WriteRejected -> "停用写入被拒绝，请到系统设置手动关闭"
                  is SecureAutomation.CapProbe.Error -> "停用失败(${r.message})，请到系统设置手动关闭"
                  is SecureAutomation.CapProbe.NoPermission -> "未授权，请到系统设置手动关闭"
              },
          )
      }
  }
  ```
- 验证方法：stub Settings 使 putString 返回 false → disable 返回 WriteRejected（单测或 androidTest）；实机：开→关 autoA11y → 系统无障碍列表同步移除 + UI 状态如实。

---

## BUG-026（P2）连接重试循环放大授权等待：最坏 ~140s 无总闸、无取消

- 涉及文件：`tuner/SecureAutomation.kt`、`tuner/TuningExecutor.kt`、`ui/TunerViewModel.kt`、`ui/TunerApp.kt`
- 修复思路（三件套）：
  1. **重试分类短路**（治本）：4 次重试只对「TCP 拒绝」（adbd 未就绪的快速失败）有意义；AUTH 等待超时（soTimeout 30s 烧完）说明 adbd 在等用户确认，立即重试只会再等 30s——短路退出，把「去头显点允许」的指引给用户。
  2. **总闸**：ensureWifiAdbThenConnect 整体 withTimeout(60s)——最坏时长从 140s 压到 60s（拒连重试 4×5s + 单次 AUTH 35s 仍在闸内）。
  3. **取消按钮**：connecting 态把「连接」按钮变成「取消」——协程 Job 持有 + cancel（ensureConnected 的 withTimeout/阻塞 read 会因 socket close 而中断，round-1 的取消语义已具备）。
- 具体改动点：

  **（a）TuningExecutor.kt【增】失败分类（A11）**：
  ```kotlin
  @Volatile var lastError: String? = null
      private set
  /** 最近一次连接失败是否为 TCP 拒绝（adbd 未监听）——重试分类用；其他失败（AUTH 超时等）不重试 */
  @Volatile var lastFailureWasRefused: Boolean = false
      private set

  fun lastErrorIsRefused(): Boolean = lastFailureWasRefused
  ```
  ensureConnected（:34-49）【改】：失败分支记录分类：
  ```kotlin
  } else {
      val e = result.exceptionOrNull()
      lastError = e?.message ?: "connect failed"
      // A11：拒连=adbd 未就绪（可重试）；超时/IOException=AUTH 等待或链路问题（不重试）
      lastFailureWasRefused = e is java.net.ConnectException
      conn.close()
      false
  }
  ```
  成功分支【增】`lastFailureWasRefused = false`。

  **（b）SecureAutomation.ensureWifiAdbThenConnect（:143-162）【改】**：总闸 + 分类短路：
  ```kotlin
  suspend fun ensureWifiAdbThenConnect(
      context: Context,
      onNote: (String) -> Unit = {},
  ): Boolean = withContext(Dispatchers.IO) {
      // 总闸：拒连重试 4×5s + 单次 AUTH 35s 均在 60s 内完成；超总闸=失败（BUG-026）
      withTimeout(60_000L) {
          val prefs = TunerPrefs.get(context)
          if (prefs.autoWifiAdb && hasWriteSecureSettings(context)) {
              when (val r = enableWifiAdb(context)) {
                  is CapProbe.Ok -> onNote("已自动开启无线调试，等待 adbd…")
                  is CapProbe.NoPermission -> onNote("未授权，走手动流程")
                  is CapProbe.WriteRejected -> onNote("无线调试写入被固件拒绝，请手动开启")
                  is CapProbe.Error -> onNote("无线调试自动开启失败(${r.message})，请手动开启")
              }
          }
          repeat(4) { attempt ->
              if (TuningExecutor.ensureConnected(context)) return@withTimeout true
              // 分类短路：非拒连失败（AUTH 等待超时/链路异常）重试无意义——
              // adbd 在等用户在头显上确认授权，立即把指引给出来
              if (!TuningExecutor.lastErrorIsRefused()) {
                  onNote("adbd 未确认授权：请在头显上点击「允许 USB 调试」，或检查无线调试状态")
                  return@withTimeout false
              }
              if (attempt < 3) delay(1_500)
          }
          false
      }
  }
  ```

  **（c）TunerViewModel.connect（:86-112）【改】**：持有 Job 支持取消：
  ```kotlin
  private var connectJob: kotlinx.coroutines.Job? = null   //【增】类成员

  fun connect() {
      if (_state.value.connecting) return
      _state.value = _state.value.copy(connecting = true, adbStatus = "连接中…")
      connectJob = viewModelScope.launch {
          val ok = SecureAutomation.ensureWifiAdbThenConnect(getApplication()) { note ->
              _state.value = _state.value.copy(adbStatus = note)
          }
          // ……成功/失败分支同现状（:93-110）……
      }
  }

  /** BUG-026：connecting 态的取消入口 */
  fun cancelConnect() {
      connectJob?.cancel()
      connectJob = null
      _state.value = _state.value.copy(
          connecting = false,
          adbStatus = "已取消连接（可重新点连接）",
      )
  }
  ```
  （ensureConnected 内 withTimeout 的取消传播：cancel → withTimeout 协程取消 → conn.connect 阻塞 read 因 socket close（round-1 已实现 close-on-cancel 路径）或 soTimeout 到期退出——不会残留挂起协程。）

  **（d）TunerApp.kt ConnectionCard（:67-69）【改】**：connecting 态变取消按钮：
  ```kotlin
  Button(
      onClick = if (state.connecting) viewModel::cancelConnect else viewModel::connect,
      enabled = !state.connected || state.connecting,
  ) {
      Text(if (state.connecting) "取消" else "连接 127.0.0.1:5555")
  }
  ```

- 风险与注意事项：
  - A11 分类依赖 ConnectException：TCP 拒绝在 JVM 抛 ConnectException（"Connection refused"）——标准行为非平台假设；但**穿透点**：若设备侧防火墙 drop（不 reject），connect 会挂到 connectTimeoutMs(5s) 抛 SocketTimeoutException → 归类为不可重试 → 短路退出。行为可接受（drop 场景 adbd 也大概率不可达），文案已含「检查无线调试状态」。
  - 总闸 60s 与内部 35s×4 的关系：分类短路后实际最坏 = 4×拒连(快速) + 1×35s AUTH + 间隔 ≈ 42s < 60s，闸是兜底而非常态路径。
  - cancelConnect 后 connectJob 引用置 null；重复 cancel 安全。
- 验证方法：
  1. 单测：lastErrorIsRefused 分类（ConnectException→true / IOException→false / TimeoutException→false）。
  2. 实机（授权等待场景）：未授权连接不点确认 → 一次 35s 超时后立即出现「请在头显上点击允许」文案（不再 4 轮×35s）；期间点「取消」→ connecting 复位、可重连。
  3. 拒连场景（adbd 未起）：4 次快速重试后失败，总时长 < 30s。

---

## BUG-027（P2）用户「断开」被 autoApply 服务逆转：重连 + 重开无线调试，UI 无感知

- 涉及文件：`tuner/TuningExecutor.kt`、`ui/TunerViewModel.kt`、`tuner/ForegroundProfileService.kt`、`tuner/BootReceiver.kt`
- 修复思路：
  1. **会话语义（核心决策）**：autoApply 的触发**要求用户显式建立过连接**。新增 `TuningExecutor.adbSessionActive` 标志：connect() 成功置 true；disconnect() 置 false（用户明确表达停止意图）；连接意外断开（runCommand 失败清理）**不置 false**（意外断开≠用户要求停止，服务下次事件可自行重连恢复服务——这正是 autoApply 的产品意义）。服务事件入口与 execute 均检查该标志，false 时 no-op。
  2. **服务建连后 UI 同步**：服务侧 execute 若发现 `connection == null` 且 sessionActive（意外断开重连场景），重连成功后通过 TuningExecutor 的 StateFlow 让 UI 感知——新增 `connectionState: StateFlow<Boolean>`（VM collect 同步 UiState.connected），解决「executor 实态与 UiState 脱节」（也是 O10 的根因）。
  3. BootReceiver 的开机恢复是**独立会话**（A5 场景，用户重启后无「断开」意图）：不检查 sessionActive，自带 ensure→执行→disconnect 全流程，结束后 sessionActive 保持 false（不开新会话）。
- 具体改动点：

  **（a）TuningExecutor.kt【增】**：
  ```kotlin
  /** 连接实态（服务侧重连后 UI 同步用，O10 根因） */
  private val _connectionState = MutableStateFlow(false)
  val connectionState: StateFlow<Boolean> = _connectionState

  /**
   * ADB 会话语义（BUG-027）：
   * true  = 用户显式连接过且未显式断开 → autoApply 服务可以工作/自行恢复连接
   * false = 用户从未连接或已显式断开 → 服务 no-op（不重连、不重开无线调试）
   * 意外断开（runCommand 失败清理）不改此标志——服务可重连自愈。
   */
  @Volatile var adbSessionActive: Boolean = false
      private set

  fun markSessionActive() { adbSessionActive = true }
  fun markSessionInactive() { adbSessionActive = false }
  ```
  connection 赋值/清空处同步 _connectionState：
  ```kotlin
  // ensureConnected 成功分支（:41-44）：
  if (result.isSuccess) {
      connection = conn
      _connectionState.value = true
      ...
  // interpretShellResult 的 IOException 清理分支（:71-74）：connection 置 null 时
  if (connection === conn) { connection = null; _connectionState.value = false }
  // disconnect()（:92-95）：
  fun disconnect() {
      connection?.close()
      connection = null
      _connectionState.value = false
  }
  ```
  （import 补 `kotlinx.coroutines.flow.MutableStateFlow / StateFlow`。）

  **（b）TunerViewModel.kt【改】**：
  - connect() 成功分支（:93-99）【增】`TuningExecutor.markSessionActive()`；
  - disconnect()（:114-125）【增】`TuningExecutor.markSessionInactive()`（在 TuningExecutor.disconnect() 之前）；
  - init【增】collect（O10 一并落地）：
    ```kotlin
    viewModelScope.launch {
        TuningExecutor.connectionState.collect { live ->
            // executor 实态同步 UI：服务侧重连/意外断开都不再脱节
            if (!live && _state.value.connected) {
                _state.value = _state.value.copy(
                    connected = false,
                    adbStatus = "连接已断开（autoApply 将在下次前台切换时自动恢复）",
                )
            } else if (live && !_state.value.connected) {
                _state.value = _state.value.copy(
                    connected = true,
                    adbStatus = "已连接（由自动应用恢复）",
                )
            }
        }
    }
    ```

  **（c）ForegroundProfileService.kt【改】**：
  - onAccessibilityEvent 入口（BUG-019 改造后的版本已含）：`if (!TuningExecutor.adbSessionActive) return`——事件直接丢弃（含 lastApplied 不更新：用户断开后手动改的任何东西都与服务无关）。
  - execute（BUG-019 改造后）的 Apply/Leave 分支【增】连接自愈（替代原 applyOrRetry:58 的 ensureWifiAdbThenConnect，仅在意外断开时）：
    ```kotlin
    private suspend fun ensureServiceConnection(): Boolean {
        // 用户显式断开 → 不重连（BUG-027 核心）
        if (!TuningExecutor.adbSessionActive) return false
        // 连接健在或意外断开自愈（不重开无线调试：只有用户 connect 流程才开）
        return TuningExecutor.ensureConnected(applicationContext)
    }
    // execute 内两个分支的命令执行前：
    val ok = ensureServiceConnection() && TuningExecutor.runCommands(...)
    ```
    **关键差异**：服务侧自愈只调 `TuningExecutor.ensureConnected`（不调 ensureWifiAdbThenConnect）——不触碰无线调试开关，不逆转用户的断开意图；无线调试的自动开启只属于用户显式 connect 流程。（意外断开后 adbd 若也关了——例如用户在系统设置关的——ensureConnected 拒连失败 → 服务本次 no-op，等下次用户显式连接。语义正确。）

  **（d）BootReceiver.kt【不改逻辑，补注释】**：开机恢复是独立一次性会话，维持现状（ensure→执行→disconnect），断开后 sessionActive=false，不激活服务会话。在 :26-34 的协程块【增】注释一行：`// 独立会话：不 markSessionActive，开机恢复后 autoApply 不自动激活（用户显式连接后激活）`。

- 风险与注意事项：
  - **语义边界明确化（lead 要求的决策点）**：autoApply 触发要求用户显式连接过（connect 成功）且未显式断开。UI 提示方式：断开时 adbStatus「已断开（无线调试已按需恢复）」+ AutomationCard 的 autoApply 开关副文案【改】为「需已连接；断开后自动应用暂停，重新连接后恢复」（TunerApp.kt/AutomationCard.kt 的 SwitchRow 文案处）。
  - sessionActive 与 a11y 服务生命周期：服务被系统杀（onDestroy）重启后 prefs 重读，sessionActive 在 executor 单例内存保持——进程不死则状态连续；进程死则全部重置（用户需重新连接，保守正确）。
  - connectionState collect 的文案会覆盖用户刚操作的状态（如「Private DNS 已设置」被「已连接（由自动应用恢复）」覆盖）——状态栏本就是最新事件语义，可接受。
- 验证方法：
  1. 实机（BUG-027 主场景）：授权+autoWifiAdb+autoApply 全开 → 连接 → 断开 → 确认无线调试已关 → 打开有 profile 的应用 → getprop 无值、系统设置无线调试仍关（修复前被重开）。
  2. 意外断开自愈：连接 → 重启 adbd → 打开有 profile 应用 → 服务 ensureConnected 自愈 → getprop 有值 + UI 状态栏「已连接（由自动应用恢复）」。
  3. 单测：sessionActive 状态机（connect→true / disconnect→false / 意外清理→不变）。

---

## BUG-028（P2）onServiceConnected 重复回调时旧 scope 未取消（协程泄漏）

- 涉及文件：`ForegroundProfileService.kt`
- 修复思路：赋值前 cancel 旧 scope；eventQueue 同步 close 重建（BUG-019 改造中已包含，见 BUG-019 (c)）。独立可验证。
- 具体改动点：见 BUG-019 (c)——`scope?.cancel()` 在新 scope 创建前；eventQueue close+新建。
- 风险与注意事项：cancel 时机在 IO 协程运行中——cancel 不等待，旧协程在下一挂起点退出；正在执行的 setprop 命令会跑完（幂等无害）。
- 验证方法：代码走查（一行防御）；极端场景模拟（系统 a11y 重连）无泄漏增长（LeakCanary 或走查确认）。

---

## 优化建议评估（O1-O10）

| # | 结论 | 理由 / 改动点 |
|---|---|---|
| O1 waitA11yRunning 窗口 3s→5s 退避 | **本轮修** | 一行级：timeoutMs 默认 5_000，delay 序列 200/400/800/1600/…（指数退避）。位置 SecureAutomation.kt:115-130：`var d = 200L` + `delay(d); d = (d * 2).coerceAtMost(1_000)`。慢固件误报减少，无风险 |
| O2 BootReceiver 4×1.5s → 30s 总时限轮询 | **本轮修** | BootReceiver.kt:26-34 的 runCatching 块外包轮询：`val deadline = now+30s; while (now<deadline) { if (ensure&&runCommands) {ok=true; break}; delay(3_000) }`。注意复用 BUG-026 改造后的 ensureWifiAdbThenConnect（内含分类短路+总闸 60s）——boot 场景闸值不同（开机慢），故 boot 侧自建 30s 轮询只调 TuningExecutor.ensureConnected（拒连才重试），不嵌套总闸。A5 语义不变 |
| O3 OvrMetrics.isAvailable remember 缓存 | **本轮修** | AutomationCard.kt:104-106：`val available = remember { OvrMetrics.isAvailable(ctx) }`。注意 ctx 作为 remember key 之一（`remember(ctx)`）防 Activity 重建持有旧 context——实际上 isAvailable 只用 packageManager 查询无状态，ctx 换新也无影响，简单 remember 即可 |
| O4 选择器 loading 态 + 防重复 | **本轮修** | UiState【增】`appsLoading: Boolean`；TunerViewModel.loadApps（:240-247）入口置 true / finally 置 false；AppPickerDialog :60-62 空列表分支改：loading 时 CircularProgressIndicator + 「加载中…」，按钮 enabled = !loading。TunerApp.kt:179 的 `if (state.apps.isEmpty()) viewModel.loadApps()` 改为带 loading 判断防重复 IPC |
| O5 PrivateDns host 格式校验 | **本轮修** | PrivateDns.kt setHostname 入口【增】：`val h = host.trim(); check(Regex("^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$").matches(h)) { "主机名格式不正确" }`（RFC 1123 近似）。UI 侧 enabled 条件加同校验（AutomationCard.kt:144）可选。拒绝走 DnsResult.Rejected 复用现有文案路径 |
| O6 versionGreaterThan pre-release 语义 | **延后** | 当前 tag 约定 vX.Y.Z（README/发布流程已隐含）；实现 semver pre-release 比较需解析 `-beta.1` 段并定义排序（alpha<beta<rc）——功能正确性影响为「该 tag 永不更新」而非错误更新，安全方向。**本轮只文档化**：SelfUpdater.kt versionGreaterThan 注释【增】「tag 命名约定 vX.Y.Z；含 pre-release 后缀的 tag 不触发自更新（防误装测试版）」——把缺陷语义转化为设计语义。完整 semver 留 round-4 |
| O7 检查更新防重入 | **本轮修** | UiState 已有 updateStatus；【增】`updateChecking: Boolean = false`；checkUpdate（:310-329）入口 `if (_state.value.updateChecking) return` + 置位/finally 复位；UpdateCard.kt:32 按钮 `enabled = !state.updateChecking` |
| O8 文案「当前 …」别扭 | **本轮修** | UpdateCard.kt:23【改】：`Text(if (state.updateStatus.isEmpty()) "手动检查更新（GitHub Releases，HTTPS）" else state.updateStatus, ...)` |
| O9 applyOrRetry 并发上限/冷却 | **不做（理由：BUG-019 方案已消解）** | Channel 单 worker + 意图折叠后，队列天然有界（16）且中间态被丢弃——快速反复切换最多堆积 16 个意图、折叠后仅执行最新一个。原建议针对的「堆积连接尝试协程」场景（每事件一个 launch）已不存在。连接失败的「冷却」仍可加（execute 失败后 worker 内 delay(10s)），但失败场景用户下次切换才是自然重试点，冷却反而延迟恢复。不修 |
| O10 服务侧建连/断连 UI 无感知 | **本轮修**（并入 BUG-027） | BUG-027 的 connectionState StateFlow + VM collect 就是本项的实现（方案 (b)）。不单独另做 |

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-3`（master 稳定，933c515 为基）。
2. **顺序**：① 019+028+020（ForegroundProfileService 重写，一次到位）→ ② 024+023（install/verify 签名改造）→ ③ 021+022 → ④ 027（sessionActive + connectionState，涉及 TuningExecutor/VM/Service/BootReceiver）→ ⑤ 026 → ⑥ 025 → ⑦ O1/O2/O3/O4/O5/O7/O8。每步 assembleDebug 可编译。
3. **019 与 027 的耦合**：ForegroundProfileService 重写时直接写入 `adbSessionActive` 检查与 `ensureServiceConnection`（BUG-027 的 (c)）——两 bug 同文件，分两次重写反而易错。但 TuningExecutor 的标志位/StateFlow 先行落地（第④步在其前不可行，调整为：第①步实施前先做 TuningExecutor 的 sessionActive/connectionState 基础设施，即顺序细化为 ⓪ TuningExecutor 基础设施 → ① Service 重写 → ②③④⑤⑥⑦）。
4. **单测**（`app/src/test/`，org.json stub 注意——本轮不涉 JSON）：`valuesMatch`（BUG-020 比对表）、`TAG_SAFE`/updateApk 穿越用例、`checkHost` 白名单、`versionGreaterThan` 既有测试保持绿、`lastErrorIsRefused` 分类、`foldPending` 意图折叠、`mergeA11yList` 既有保持绿。
5. **不改动**：AdbProtocol/AdbConnection/AdbKeyStore（round-1 成果）、SetupWizard、AppScanner、R8 配置（David 已核对无问题）。
6. **交付后自验清单**：
   1. 快速切换回归（019）：开 profile 应用 1s 内 Home → 参数恢复默认；快速 A→B→桌面只执行最终态（a11y.log 记录数）
   2. 手动保护（020）：服务应用 B → UI 手动 120Hz → Home → 120Hz 保留 + skip rollback 日志
   3. 更新链（021/022/023/024）：mock 白名单外 URL 拒绝；非法 tag 拒绝；StrictMode 无违规；磁盘满不崩溃
   4. 无障碍对称探测（025）：关 autoA11y 后系统列表同步 + 状态如实
   5. 连接总闸（026）：未授权连接 35s 一次超时即出指引（非 140s）；取消按钮即时生效；拒连场景快速重试
   6. 断开语义（027）：断开后切前台应用无重连无重开无线调试；意外断开自愈 + UI 同步
   7. 泄漏（028）：a11y 反复开关无协程泄漏
   8. O1-O8 各自的功能点抽查
