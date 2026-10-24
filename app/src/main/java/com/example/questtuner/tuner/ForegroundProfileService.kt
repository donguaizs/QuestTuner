package com.example.questtuner.tuner

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /** 上一次由本服务应用过 profile 的包；前台离开它时只回滚这部分 */
    private var lastAppliedPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = ProfileRepository.of(applicationContext.filesDir)
        prefs = TunerPrefs.get(applicationContext)
        log("service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 开关隔离：autoApply 关闭时服务 no-op（服务可能仍被系统保持连接）
        if (prefs?.autoApplyProfile != true) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == applicationContext.packageName) return
        val r = repo ?: return
        val scope = scope ?: return
        // 防抖在 XML notificationTimeout 已做系统级节流；此处做包级去重
        if (pkg == lastAppliedPkg) return

        val profile = r.find(pkg)
        if (profile != null) {
            lastAppliedPkg = pkg
            scope.launch { applyOrRetry(pkg, profile.toCommands()) }
        } else if (lastAppliedPkg != null) {
            // 离开了有 profile 的应用 → 仅回滚我们应用过的（resetAll）
            val prev = lastAppliedPkg
            lastAppliedPkg = null
            prev?.let { scope.launch { applyOrRetry(it, TuningCommands.resetAll()) } }
        }
    }

    private suspend fun applyOrRetry(pkg: String, cmds: List<String>) {
        val ctx = applicationContext
        val ok = SecureAutomation.ensureWifiAdbThenConnect(ctx) &&
            TuningExecutor.runCommands(cmds)
        if (!ok) {
            // 失败允许下次前台切换重试
            if (lastAppliedPkg == pkg) lastAppliedPkg = null
            log("apply failed for $pkg: ${TuningExecutor.lastError}")
        } else {
            log("applied $pkg (${cmds.size} cmds)")
        }
    }

    override fun onInterrupt() { /* 无可中断操作 */ }

    override fun onDestroy() {
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
