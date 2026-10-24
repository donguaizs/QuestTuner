package com.example.questtuner.tuner

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** WRITE_SECURE_SETTINGS 自动化：每个操作 = 尝试 + 回读探测，结果用 CapProbe 如实返回。 */
object SecureAutomation {

    /** PC 端一次性授权命令（UI 展示 + 复制用） */
    const val GRANT_CMD =
        "adb shell pm grant com.example.questtuner android.permission.WRITE_SECURE_SETTINGS"

    /** 「本 app 开启过无线调试」的内存标记：断开时只回滚自己开的，不动用户手动开的 */
    @Volatile
    var wifiAdbEnabledByUs = false
        private set

    sealed class CapProbe {
        object Ok : CapProbe()
        object NoPermission : CapProbe()
        object WriteRejected : CapProbe()
        data class Error(val message: String?) : CapProbe()
    }

    // ---- A1 探测：授权状态（documented API，非假设） ----
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.packageManager.checkPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
            context.packageName,
        ) == PackageManager.PERMISSION_GRANTED

    // ---- A2：无线调试开关 ----
    // 假设A2：Quest 固件接受 adb_wifi_enabled 并开启 5555 adbd（QGO 同款）
    // 探测：putInt 后 getInt 回读
    // 失败：CapProbe.WriteRejected/Error → 调用方降级手动流程
    fun enableWifiAdb(context: Context): CapProbe {
        if (!hasWriteSecureSettings(context)) return CapProbe.NoPermission
        return runCatching {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
                wifiAdbEnabledByUs = true
                CapProbe.Ok
            } else {
                CapProbe.WriteRejected
            }
        }.getOrElse { CapProbe.Error(it.message) }
    }

    fun disableWifiAdb(context: Context): CapProbe {
        if (!hasWriteSecureSettings(context)) return CapProbe.NoPermission
        return runCatching {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            wifiAdbEnabledByUs = false
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", -1) == 0) CapProbe.Ok
            else CapProbe.WriteRejected
        }.getOrElse { CapProbe.Error(it.message) }
    }

    /** 断开时的安全回滚：只关自己开的 */
    fun disableWifiAdbIfWeEnabled(context: Context) {
        if (wifiAdbEnabledByUs) {
            disableWifiAdb(context)
            wifiAdbEnabledByUs = false
        }
    }

    // ---- A4：无障碍服务自动启用（追加式，不覆盖其他服务） ----
    // 假设A4：写入 enabled_accessibility_services + accessibility_enabled 后即时生效
    // 探测：轮询 AccessibilityManager.getEnabledAccessibilityServiceList
    // 失败：返回 false → 调用方提示手动到系统设置开启
    fun a11yComponentName(context: Context): ComponentName =
        ComponentName(context, ForegroundProfileService::class.java)

    fun enableA11yService(context: Context): CapProbe {
        if (!hasWriteSecureSettings(context)) return CapProbe.NoPermission
        return runCatching {
            val cr = context.contentResolver
            val flat = a11yComponentName(context).flattenToString()
            val cur = Settings.Secure.getString(cr, "enabled_accessibility_services") ?: ""
            if (!cur.split(':').map { it.trim() }.contains(flat)) {
                val next = mergeA11yList(cur, flat)
                Settings.Secure.putString(cr, "enabled_accessibility_services", next)
            }
            Settings.Secure.putString(cr, "accessibility_enabled", "1")
            // 回读（存储层确认）
            val stored = Settings.Secure.getString(cr, "enabled_accessibility_services") ?: ""
            if (!stored.split(':').map { it.trim() }.contains(flat)) CapProbe.WriteRejected
            else CapProbe.Ok
        }.getOrElse { CapProbe.Error(it.message) }
    }

    fun disableA11yService(context: Context): CapProbe {
        if (!hasWriteSecureSettings(context)) return CapProbe.NoPermission
        return runCatching {
            val cr = context.contentResolver
            val flat = a11yComponentName(context).flattenToString()
            val cur = Settings.Secure.getString(cr, "enabled_accessibility_services") ?: ""
            val rest = cur.split(':').map { it.trim() }
                .filter { it.isNotEmpty() && it != flat }.joinToString(":")
            Settings.Secure.putString(cr, "enabled_accessibility_services", rest)
            if (rest.isEmpty()) Settings.Secure.putString(cr, "accessibility_enabled", "0")
            CapProbe.Ok
        }.getOrElse { CapProbe.Error(it.message) }
    }

    /** A4 行为级探测：服务是否真正 running（区别于存储层已写入） */
    suspend fun waitA11yRunning(context: Context, timeoutMs: Long = 3_000): Boolean =
        withContext(Dispatchers.IO) {
            val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            val cn = a11yComponentName(context)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val list = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                if (list.any { it.resolveInfo.serviceInfo.componentName == cn }) {
                    return@withContext true
                }
                delay(200)
            }
            false
        }

    fun isA11yRunning(context: Context): Boolean {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val cn = a11yComponentName(context)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.componentName == cn }
    }

    // ---- 组合入口：P0-A 主路径（VM 与无障碍服务共用） ----
    // 假设A3：写入后 adbd 数秒内就绪 → 重试连接（最多 4 次、间隔 1.5s；TCP 拒连是快速失败）
    // 失败：返回 false，note 带降级原因
    suspend fun ensureWifiAdbThenConnect(
        context: Context,
        onNote: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val prefs = TunerPrefs.get(context)
        if (prefs.autoWifiAdb && hasWriteSecureSettings(context)) {
            when (val r = enableWifiAdb(context)) {
                is CapProbe.Ok -> onNote("已自动开启无线调试，等待 adbd…")
                is CapProbe.NoPermission -> onNote("未授权，走手动流程")
                is CapProbe.WriteRejected -> onNote("无线调试写入被固件拒绝，请手动开启")
                is CapProbe.Error -> onNote("无线调试自动开启失败(${r.message})，请手动开启")
            }
        }
        // 无论自动化成功与否都继续尝试连接（用户可能本来就手动开着）
        repeat(4) { attempt ->
            if (TuningExecutor.ensureConnected(context)) return@withContext true
            if (attempt < 3) delay(1_500)
        }
        false
    }

    // ---- 纯函数（可单测）：合并无障碍服务列表 ----
    fun mergeA11yList(current: String, ours: String): String =
        if (current.isBlank()) ours else "$current:$ours"
}
