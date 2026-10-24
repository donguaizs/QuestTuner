package com.example.questtuner.tuner

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 前台应用自动应用 Profile。
 * 隐私：只消费 event.packageName；canRetrieveWindowContent=false，不读取任何窗口内容。
 */
class ForegroundProfileService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var repo: ProfileRepository? = null
    private var prefs: TunerPrefs? = null

    /** 意图队列：单 worker 串行消费，保证事件序 == 执行序（BUG-019） */
    private var eventQueue: Channel<ApplyIntent>? = null

    /**
     * 上一次由本服务应用过 profile 的包及当时的 profile（BUG-020 值比对用）。
     * 读写在事件入口（主线程）与 worker（IO）并发：交错影响限于极端时序下
     * 多执行/少执行一次幂等 setprop，无害，不额外加锁（避免复杂化）。
     */
    private var lastApplied: AppliedRecord? = null

    /** 执行意图：进有 profile 应用=应用；离开=回滚（仅当值仍属我们） */
    private sealed class ApplyIntent {
        data class Apply(val pkg: String, val cmds: List<String>, val profile: AppProfile) : ApplyIntent()
        data class Leave(val fromPkg: String) : ApplyIntent()
    }

    private data class AppliedRecord(
        val pkg: String,
        val profile: AppProfile,
        /** 应用时的手动代次：UI 手动应用后 generation 变化 → Leave 不回滚（BUG-020 双保险之二） */
        val generation: Int,
    )

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
                // 防御：execute 内部已全 Result 风格，此处是 worker 不被异常杀死的最后防线
                runCatching { execute(current) }.onFailure { log("worker error: ${it.message}") }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 开关隔离：autoApply 关闭时服务 no-op（服务可能仍被系统保持连接）
        if (prefs?.autoApplyProfile != true) return
        // BUG-027：用户显式断开后服务 no-op（含 lastApplied 不更新——
        // 用户断开后手动改的任何东西都与服务无关）
        if (!TuningExecutor.adbSessionActive) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == applicationContext.packageName) return
        val r = repo ?: return
        val q = eventQueue ?: return
        // 防抖：包级去重（同包不重复入队）
        if (pkg == lastApplied?.pkg) return

        val profile = r.find(pkg)
        if (profile != null) {
            // 记录值快照与代次，回滚比对用（BUG-020）
            lastApplied = AppliedRecord(pkg, profile, TuningExecutor.manualGeneration)
            q.trySend(ApplyIntent.Apply(pkg, profile.toCommands(), profile))
        } else if (lastApplied != null) {
            val prev = lastApplied!!
            lastApplied = null
            q.trySend(ApplyIntent.Leave(prev.pkg))
        }
        // trySend 失败（队列满 16）= 系统事件洪泛，丢弃旧意图安全（最新意图会在队列腾空后到达）
    }

    private suspend fun execute(intent: ApplyIntent) {
        when (intent) {
            is ApplyIntent.Apply -> {
                val ok = ensureServiceConnection() && TuningExecutor.runCommands(intent.cmds)
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
                if (!stillOurValues()) {
                    log("skip rollback for ${intent.fromPkg}: values changed by user")
                    return
                }
                val ok = ensureServiceConnection() &&
                    TuningExecutor.runCommands(TuningCommands.resetAll())
                log(if (ok) "rolled back ${intent.fromPkg}" else "rollback failed: ${TuningExecutor.lastError}")
            }
        }
    }

    /**
     * BUG-027 服务侧连接自愈：
     * 用户显式断开 → 不重连（核心语义）；
     * 连接健在或意外断开 → 仅 TuningExecutor.ensureConnected 自愈——
     * 不触碰无线调试开关（自动开启只属于用户显式 connect 流程），不逆转用户的断开意图。
     */
    private suspend fun ensureServiceConnection(): Boolean {
        if (!TuningExecutor.adbSessionActive) return false
        return TuningExecutor.ensureConnected(applicationContext)
    }

    /**
     * BUG-020 值比对：getprop 读当前值，与我们应用过的 profile 期望值逐项对比。
     * 假设A10：getprop 输出 == setprop 写入的确切值（同值往返）。
     * 探测：比对本身；任一项不匹配 → 视为用户手动改动，返回 false。
     * 失败（getprop 读不到/连接断/代次变化）：返回 false——读不到就无法证明「值仍属我们」，
     * 宁可不回滚也不误清；残留参数由用户手动恢复默认或进有 profile 应用覆盖，可自愈
     * 且不违背「不清用户手动设置」承诺。
     */
    private suspend fun stillOurValues(): Boolean {
        val rec = lastApplied ?: return false
        // 双保险之二：UI 手动应用后代次变化 → 当前值以用户意志为准，不回滚
        if (TuningExecutor.manualGeneration != rec.generation) return false
        val out = TuningExecutor.runCommand(TuningCommands.statusCommand()) ?: return false
        return valuesMatch(rec.profile, out.split("---").map { it.trim() })
    }

    companion object {
        /**
         * 纯函数（可单测）：statusCommand 输出分段与 profile 期望值比对。
         * parts 序与 TuningCommands.statusCommand 的属性序一致：
         * [0]=refreshRate [1]=textureWidth [2]=textureHeight [3]=cpuLevel [4]=gpuLevel [5]=highPrecisionPassthrough
         */
        fun valuesMatch(profile: AppProfile, parts: List<String>): Boolean {
            if (parts.size < 6) return false
            profile.refreshRate?.let { if (parts[0] != it.toString()) return false }
            profile.textureWidth?.let { if (parts[1] != it.toString()) return false }
            profile.textureHeight?.let { if (parts[2] != it.toString()) return false }
            profile.cpuLevel?.let { if (parts[3] != it.toString()) return false }
            profile.gpuLevel?.let { if (parts[4] != it.toString()) return false }
            return true
        }
    }

    override fun onInterrupt() { /* 无可中断操作 */ }

    override fun onDestroy() {
        eventQueue?.close()
        eventQueue = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    /** 简单本地日志（循环截断到 64KB），便于无 logcat 的头显侧排查 */
    private fun log(msg: String) {
        runCatching {
            val f = File(applicationContext.filesDir, "a11y.log")
            if (f.length() > 64_000) f.writeText("")   // 粗截断
            f.appendText("${System.currentTimeMillis()} $msg\n")
        }
    }
}
