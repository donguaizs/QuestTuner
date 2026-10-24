package com.example.questtuner.tuner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * OVR Metrics (com.oculus.ovrmonitormetricsservice) 叠加层控制。
 *
 * ⚠️ 假设A7（全部未验证，仅 QGO 字节码线索）：
 *   - receiver 类名：任务描述中类名被省略为 "...SettingsBroadcastReceiver"，
 *     常量按 OVR Metrics 公开惯例填 "com.oculus.ovrmonitormetricsservice.SettingsBroadcastReceiver"
 *   - action 后缀：ENABLE_OVERLAY / ENABLE_GRAPH / ENABLE_STAT（QGO 字节码已见）
 *   - extras 键名：未验证，默认尝试 "enabled"（Boolean）
 * 探测：包安装检测（确定）；广播效果无法在 app 内确认（广播无回执）
 * 失败：叠加层不出现 → UI 如实说明；常量修正只需改本文件三个常量
 */
object OvrMetrics {
    private const val TARGET_PKG = "com.oculus.ovrmonitormetricsservice"
    // TODO(验证后修正): receiver 类名，验证方法见 PLAN-round2.md P2-1 节脚本
    private const val TARGET_CLS = "com.oculus.ovrmonitormetricsservice.SettingsBroadcastReceiver"
    private const val ACTION_PREFIX = "com.oculus.ovrmonitormetricsservice."
    // TODO(验证后修正): extras 键名
    private const val EXTRA_ENABLED = "enabled"

    /** 确定可探测：OVR Metrics 服务包是否安装 */
    fun isAvailable(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TARGET_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun send(context: Context, suffix: String, enabled: Boolean) {
        val intent = Intent(ACTION_PREFIX + suffix)
            .setComponent(ComponentName(TARGET_PKG, TARGET_CLS))
            .putExtra(EXTRA_ENABLED, enabled)
        // 显式广播：组件不存在时系统静默丢弃（这正是无法探测的原因）
        context.sendBroadcast(intent)
    }

    fun applyState(context: Context, overlay: Boolean, graph: Boolean, stat: Boolean) {
        send(context, "ENABLE_OVERLAY", overlay)
        send(context, "ENABLE_GRAPH", graph)
        send(context, "ENABLE_STAT", stat)
    }
}
