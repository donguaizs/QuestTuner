package com.example.questtuner.tuner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 开机恢复默认参数（可选开关，默认关）。
 * 假设A5：无线调试跨重启保持开启（曾由 P0-A 开启或用户手动开启），5555 可连。
 * 探测：ensureConnected 结果写入本地日志（无弹窗——开机场景避免打扰）。
 * 失败：静默放弃，等用户下次打开 app；重试=下次重启。
 *
 * 实现说明：goAsync 的 10s 窗口装不下最坏 35s 的 AUTH 等待，故不持住 pendingResult——
 * 拉起协程后立即返回；进程若被杀则本次恢复丢失，属 best-effort 设计（开关文案已声明）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TunerPrefs.get(context).restoreOnBoot) return
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = runCatching {
                SecureAutomation.ensureWifiAdbThenConnect(appContext) &&
                    TuningExecutor.runCommands(TuningCommands.resetAll())
            }.getOrDefault(false)
            TuningExecutor.disconnect()
            SecureAutomation.disableWifiAdbIfWeEnabled(appContext)
            log(appContext, if (ok) "boot restore ok" else "boot restore failed: ${TuningExecutor.lastError}")
        }
    }

    private fun log(context: Context, msg: String) {
        runCatching {
            val f = File(context.filesDir, "boot.log")
            if (f.length() > 16_000) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $msg\n")
        }
    }
}
