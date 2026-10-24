# QuestTuner Round 2 实施方案（PLAN-round2）

- 制定：Li（技术方案）
- 依据：lead 的 QGO 14.4.3 逆向结论（已核实）+ 任务板 task-5 描述 + 补充约束（无实机预验证，直接实施）
- 基线：round-1 修复后的 master；修复在 `fix/round-2` 分支进行
- 本方案行号基于 round-1 修复后的当前源码核对；Wang 改动时如行号漂移请按代码片段定位
- 标注【改】给出改动后代码；【删】直接删除；【增】为新增行/文件
- **硬约束（贯穿全方案）**：调参本身仍必须走 ADB shell setprop（已核实 QGO 从未用 Settings API 写 debug.oculus.*），不做「免 ADB 调参」
- **硬约束（lead 补充）**：所有依赖 Quest/OVR 平台行为的能力 = 运行时能力探测（写后回读）+ 失败降级到手动流程 + 独立开关（默认关闭）+ 方案内逐处标注「假设/探测/失败时行为」。不为「看起来能跑」把假设写成事实

## 总览

| 项 | 级别 | 结论 | 新增文件 | 修改文件 |
|---|---|---|---|---|
| P0-A 一次性授权自动化 | P0 | 实施 | tuner/TunerPrefs.kt、tuner/SecureAutomation.kt | TunerViewModel.kt、TunerApp.kt、AndroidManifest.xml |
| P0-B 可选无障碍自动应用 | P0 | 实施 | tuner/ForegroundProfileService.kt、res/xml/accessibility_service_config.xml、res/values/strings.xml | AndroidManifest.xml、TunerViewModel.kt |
| P1-1 应用选择器（修 BUG-017） | P1 | 实施 | ui/AppPickerDialog.kt | TunerApp.kt、TunerViewModel.kt |
| P1-2 自更新 | P1 | 实施 | update/SelfUpdater.kt、update/InstallResultReceiver.kt、ui/UpdateCard.kt | AndroidManifest.xml、TunerViewModel.kt、TunerApp.kt、app/build.gradle.kts |
| P2-1 OVR metrics 叠加层开关 | P2 | 实施（键名全标注假设） | tuner/OvrMetrics.kt | ui/AutomationCard.kt（合并入 P0-A 的卡片） |
| P2-2 Private DNS / 首次向导 / 开机恢复 | P2 | 实施 | tuner/PrivateDns.kt、tuner/BootReceiver.kt、ui/SetupWizard.kt | AndroidManifest.xml、TunerViewModel.kt、TunerApp.kt |
| P3 R8 release 工程化 | P3 | 实施 | app/proguard-rules.pro | app/build.gradle.kts |
| 明确不做 | — | cleartext NSC、FileProvider external-path "."、deviceId/包名上报、MANAGE_EXTERNAL_STORAGE | — | — |

**建议实施顺序**（每步独立可编译）：① TunerPrefs + SecureAutomation（P0-A 核心，纯逻辑）→ ② ForegroundProfileService + manifest + config（P0-B）→ ③ UiState/ViewModel 接线 + AutomationCard + SetupWizard（P0-A/B UI 与降级文案）→ ④ AppPickerDialog（P1-1）→ ⑤ SelfUpdater + InstallResultReceiver + UpdateCard（P1-2）→ ⑥ OvrMetrics（P2-1，并入 AutomationCard）→ ⑦ PrivateDns + BootReceiver（P2-2）→ ⑧ gradle/R8（P3，最后做，避免 minify 干扰前期调试）。

---

## 假设台账（所有平台行为依赖集中登记）

每条格式：假设内容 / 探测方法 / 失败时行为。各项详细方案中引用编号。

| # | 假设 | 运行时探测方法 | 失败时行为 |
|---|---|---|---|
| A1 | `pm grant com.example.questtuner android.permission.WRITE_SECURE_SETTINGS` 在 Quest 固件可成功授予（标准 Android development 权限） | `PackageManager.checkPermission(WRITE_SECURE_SETTINGS, 本包) == PERMISSION_GRANTED` | 自动化区全部禁用置灰，仅展示可复制的授权命令与手动流程说明 |
| A2 | `Settings.Global.putInt("adb_wifi_enabled", 1)` 使 adbd 监听 127.0.0.1:5555（QGO 同款行为） | putInt 后立即 `getInt` 回读；随后 TCP 重试连接（A3） | 回读不符或抛 SecurityException → 状态栏提示「自动开启失败，请手动开启无线调试」，继续尝试现有手动连接路径 |
| A3 | 写入 adb_wifi_enabled 后 adbd 数秒内就绪监听 | 连接重试循环：最多 4 次、间隔 1.5s（TCP 拒连是快速失败） | 超时 → 提示手动开启无线调试后重按连接；不崩溃 |
| A4 | `Settings.Secure` 写 enabled_accessibility_services（追加式）+ accessibility_enabled=1 即时生效、无需重启 | `AccessibilityManager.getEnabledAccessibilityServiceList()` 轮询本服务组件（最多 3s，200ms 间隔） | 未生效 → 提示「请到 系统设置 → 无障碍 手动开启 QuestTuner 服务」，服务开关保持用户预期但 UI 如实显示未运行 |
| A5 | adb_wifi_enabled=1 跨重启保持（无线调试常开），开机后 5555 可连 | BootReceiver 中 ensureConnected 的结果（写入 app 内日志，不弹窗） | 失败 → 静默放弃本次开机恢复，等用户下次打开 app（无崩溃、无提示轰炸） |
| A6 | Quest 固件对 PackageInstaller 会话弹出系统安装确认界面（2D 面板） | 安装会话回调：STATUS_PENDING_USER_ACTION → 拉起系统确认页；STATUS_SUCCESS/FAILURE 有终态 | 无确认页/失败 → 已安装的旧版本原样保留（PackageInstaller 原地更新、失败不动旧包 = 天然回滚），提示「请用 PC adb install 手动更新」 |
| A7 | OVR metrics 接收器类名/action 后缀（ENABLE_OVERLAY/ENABLE_GRAPH/ENABLE_STAT）/extras 键名 —— **全部未验证** | 可确定部分：`pm.getPackageInfo("com.oculus.ovrmonitormetricsservice")` 是否安装（包不存在 → 整区块禁用）；**广播效果在 app 内无法确认**（广播投递无回执） | 叠加层未出现 → UI 文案如实说明「已发送广播，无法确认效果，请确认 OVR Metrics Tool 状态」；附验证脚本（见 P2-1 节） |
| A8 | Quest 遵循 AOSP 标准 `private_dns_mode` / `private_dns_specifier` 行为 | 写入后回读两键 | 回读不符 → 自动还原写入前的旧值 + 错误提示；不崩溃 |
| A9 | debug.oculus.* setprop 为全局作用域，前台切换时重设即生效（round-1 已实机确认的前提，此处沿用） | statusCommand（getprop 聚合回读，已有） | — |

> 约定：A1–A8 均不因「QGO 是这么做的」而当作事实。QGO 证据只决定「值得做这个尝试」，运行时探测结果才是事实源。

---

## P0-A 一次性授权后的自动化（WRITE_SECURE_SETTINGS）

### 涉及文件

- 新增：`app/src/main/java/com/example/questtuner/tuner/TunerPrefs.kt`
- 新增：`app/src/main/java/com/example/questtuner/tuner/SecureAutomation.kt`
- 修改：`ui/TunerViewModel.kt`（connect 流程 + disconnect + 状态）、`ui/TunerApp.kt`（AutomationCard + 断开按钮）、`AndroidManifest.xml`（无新增权限，WRITE_SECURE_SETTINGS 是 grant-only 权限，声明与否不影响 pm grant；**不声明**，避免用户误解为运行时弹窗权限）

### 设计

1. **能力分层**：`hasWriteSecureSettings`（A1 探测，documented API 非假设）是总开关；未授予时所有自动化置灰，仅显示可复制命令。
2. **无线调试自动化**（A2+A3）：connect 前按开关尝试 `adb_wifi_enabled=1`，写后回读；随后带重试的连接；断开时仅回滚「由我们开启的」（内存标记，不误关用户手动开的）。
3. **无障碍自动启用**（A4）：追加式写入（不覆盖用户已启用的其他服务），轮询确认服务真正 running。
4. **降级原则**：任何探测失败 → 继续走 round-1 现有手动路径（用户手动开无线调试 → 点连接），UI 文案说明原因。自动化是加速器，不是依赖项。
5. 调参命令路径（TuningExecutor/setprop）完全不动。

### 具体改动点

**（a）【增】`tuner/TunerPrefs.kt`**（全量新文件，SharedPreferences 保证 receiver/service 无协程也能同步读）：

```kotlin
package com.example.questtuner.tuner

import android.content.Context

/** 用户设置（全部默认关闭 = 开关隔离）。SharedPreferences：BootReceiver/无障碍服务需同步读。 */
class TunerPrefs private constructor(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("tuner_prefs", Context.MODE_PRIVATE)

    var autoWifiAdb: Boolean
        get() = sp.getBoolean(K_AUTO_WIFI_ADB, false)
        set(v) = sp.edit().putBoolean(K_AUTO_WIFI_ADB, v).apply()
    var autoA11y: Boolean
        get() = sp.getBoolean(K_AUTO_A11Y, false)
        set(v) = sp.edit().putBoolean(K_AUTO_A11Y, v).apply()
    var autoApplyProfile: Boolean
        get() = sp.getBoolean(K_AUTO_APPLY, false)
        set(v) = sp.edit().putBoolean(K_AUTO_APPLY, v).apply()
    var restoreOnBoot: Boolean
        get() = sp.getBoolean(K_RESTORE_ON_BOOT, false)
        set(v) = sp.edit().putBoolean(K_RESTORE_ON_BOOT, v).apply()
    var firstRunDone: Boolean
        get() = sp.getBoolean(K_FIRST_RUN_DONE, false)
        set(v) = sp.edit().putBoolean(K_FIRST_RUN_DONE, v).apply()

    companion object {
        private const val K_AUTO_WIFI_ADB = "auto_wifi_adb"
        private const val K_AUTO_A11Y = "auto_a11y"
        private const val K_AUTO_APPLY = "auto_apply_profile"
        private const val K_RESTORE_ON_BOOT = "restore_on_boot"
        private const val K_FIRST_RUN_DONE = "first_run_done"
        fun get(context: Context): TunerPrefs = TunerPrefs(context)
    }
}
```

**（b）【增】`tuner/SecureAutomation.kt`**（全量新文件，P0-A/P0-B/P2-2 共用）：

```kotlin
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
```

**（c）修改 `ui/TunerViewModel.kt`**：

- :18-26 UiState【增】字段（追加到 data class 末尾）：
```kotlin
    val hasSecureGrant: Boolean = false,   // A1 探测结果
    val autoWifiAdb: Boolean = false,
    val autoA11y: Boolean = false,
    val autoApply: Boolean = false,        // P0-B
    val restoreOnBoot: Boolean = false,    // P2-2
    val a11yRunning: Boolean = false,      // A4 行为级探测结果
    val wizardVisible: Boolean = false,    // P2-2 首次向导
```

- :30 附近类成员【增】：`private val prefs = TunerPrefs.get(app)`

- :34-39 init【改】为：
```kotlin
    init {
        viewModelScope.launch {
            val ps = withContext(Dispatchers.IO) { repo.profiles() }
            _state.value = _state.value.copy(
                profiles = ps,
                hasSecureGrant = SecureAutomation.hasWriteSecureSettings(app),
                autoWifiAdb = prefs.autoWifiAdb,
                autoA11y = prefs.autoA11y,
                autoApply = prefs.autoApplyProfile,
                restoreOnBoot = prefs.restoreOnBoot,
                a11yRunning = SecureAutomation.isA11yRunning(app),
                wizardVisible = !prefs.firstRunDone,
            )
        }
    }
```

- :41-62 connect()【改】：`ensureConnected` 换为 `SecureAutomation.ensureWifiAdbThenConnect`，note 实时更新 adbStatus：
```kotlin
    fun connect() {
        if (_state.value.connecting) return
        _state.value = _state.value.copy(connecting = true, adbStatus = "连接中…")
        viewModelScope.launch {
            val ok = SecureAutomation.ensureWifiAdbThenConnect(getApplication()) { note ->
                _state.value = _state.value.copy(adbStatus = note)
            }
            if (ok) {
                refreshStatus()
                _state.value = _state.value.copy(
                    connected = true,
                    connecting = false,
                    adbStatus = "已连接 adbd (127.0.0.1:5555)",
                )
                loadApps()
            } else {
                // 降级文案：明确告诉用户手动路径
                _state.value = _state.value.copy(
                    connected = false,
                    connecting = false,
                    adbStatus = "连接失败: ${TuningExecutor.lastError}\n" +
                        "请手动开启无线调试（系统设置 → 开发者选项）后重试，" +
                        "或用 PC 执行授权命令后开启自动连接",
                )
            }
        }
    }
```

- 【增】新函数（放在 connect() 之后）：
```kotlin
    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            TuningExecutor.disconnect()
            SecureAutomation.disableWifiAdbIfWeEnabled(getApplication())
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    connected = false,
                    adbStatus = "已断开（无线调试已按需恢复）",
                )
            }
        }
    }

    /** 开关统一入口：改 prefs → 立即执行探测 → 状态回填 UI */
    fun setAutoWifiAdb(enabled: Boolean) {
        prefs.autoWifiAdb = enabled
        _state.value = _state.value.copy(autoWifiAdb = enabled)
    }

    fun setAutoA11y(enabled: Boolean) {
        prefs.autoA11y = enabled
        _state.value = _state.value.copy(autoA11y = enabled)
        if (enabled) viewModelScope.launch {
            if (SecureAutomation.hasWriteSecureSettings(getApplication())) {
                when (SecureAutomation.enableA11yService(getApplication())) {
                    is SecureAutomation.CapProbe.Ok -> {
                        // A4 行为级确认：存储层 OK ≠ 服务已起
                        val running = SecureAutomation.waitA11yRunning(getApplication())
                        _state.value = _state.value.copy(
                            a11yRunning = running,
                            adbStatus = if (running) "无障碍服务已自动启用"
                                else "无障碍已写入但服务未运行，请到系统设置手动开启",
                        )
                    }
                    else -> _state.value = _state.value.copy(
                        a11yRunning = false,
                        adbStatus = "无障碍自动启用失败，请到 系统设置 → 无障碍 手动开启",
                    )
                }
            } else {
                _state.value = _state.value.copy(
                    a11yRunning = false,
                    adbStatus = "未授权 WRITE_SECURE_SETTINGS，请到 系统设置 → 无障碍 手动开启",
                )
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                SecureAutomation.disableA11yService(getApplication())
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(a11yRunning = false)
                }
            }
        }
    }

    fun setAutoApply(enabled: Boolean) {
        prefs.autoApplyProfile = enabled
        _state.value = _state.value.copy(autoApply = enabled)
    }

    fun setRestoreOnBoot(enabled: Boolean) {
        prefs.restoreOnBoot = enabled
        _state.value = _state.value.copy(restoreOnBoot = enabled)
    }

    fun dismissWizard() {
        prefs.firstRunDone = true
        _state.value = _state.value.copy(wizardVisible = false)
    }
```

**（d）修改 `ui/TunerApp.kt`**：

- :32-50 LazyColumn【增】两个 item（插在 `item { QuickTuneCard(...) }` 之后）：
```kotlin
            item { AutomationCard(state, viewModel) }
            item { UpdateCard(state, viewModel) }   // P1-2，见该节
```
- ConnectionCard :61-67【增】断开按钮（Row 内追加）：
```kotlin
                OutlinedButton(onClick = viewModel::disconnect, enabled = state.connected) {
                    Text("断开")
                }
```

**（e）【增】`ui/AutomationCard.kt`**（P0-A 开关区 + P2-1 metrics + P2-2 PrivateDNS 合并卡片，全量新文件）：

```kotlin
package com.example.questtuner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.questtuner.tuner.SecureAutomation

/** 自动化与增强：每个能力独立开关（默认关），探测结果如实展示，失败给手动路径。 */
@Composable
fun AutomationCard(state: UiState, viewModel: TunerViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自动化与增强", style = MaterialTheme.typography.titleMedium)

            if (!state.hasSecureGrant) {
                // A1 探测失败：自动化区置灰，展示可复制命令（降级路径的一部分）
                Text(
                    "未授予 WRITE_SECURE_SETTINGS（一次性，PC 执行后自动功能可用）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                val clip = LocalClipboardManager.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clip.setText(AnnotatedString(SecureAutomation.GRANT_CMD))
                    }) { Text("复制授权命令") }
                }
                Text(
                    SecureAutomation.GRANT_CMD,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val grant = state.hasSecureGrant
            SwitchRow(
                "连接时自动开启无线调试",
                "断开时自动恢复（仅关闭由本应用开启的）",
                state.autoWifiAdb, grant,
            ) { viewModel.setAutoWifiAdb(it) }
            SwitchRow(
                "自动启用无障碍服务",
                if (state.a11yRunning) "服务运行中" else "未运行（可在系统设置手动开启）",
                state.autoA11y, grant,
            ) { viewModel.setAutoA11y(it) }
            SwitchRow(
                "按前台应用自动应用 Profile",
                "仅本地监听窗口切换，不上传任何数据；需无障碍服务",
                state.autoApply, state.autoA11y || grant,
            ) { viewModel.setAutoApply(it) }
            SwitchRow(
                "开机恢复默认参数",
                "重启后自动清除调参属性（需无线调试可用）",
                state.restoreOnBoot, grant,
            ) { viewModel.setRestoreOnBoot(it) }

            OvrMetricsSection(state, viewModel)   // P2-1，见该节
            PrivateDnsSection(state, viewModel)   // P2-2，见该节
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = if (enabled) onChange else null)
    }
}
```

> OvrMetricsSection / PrivateDnsSection 的实现分别在 P2-1 / P2-2 节给出。

### 假设/探测/失败（P0-A 汇总）

- A1 授权状态：checkPermission（非假设，documented API）→ 未授权整个区域只读展示命令。
- A2 无线调试：putInt+getInt 回读 → 失败降级手动 + 文案。
- A3 adbd 就绪：4×1.5s 重试 → 超时走手动文案。
- A4 无障碍：写入回读 + getEnabledAccessibilityServiceList 行为级轮询（3s）→ 失败提示系统设置手动路径。
- 断开回滚只针对 `wifiAdbEnabledByUs == true` 的情形（用户手动开的无线调试绝不被我们关闭）。

### 风险与注意事项

- `enabled_accessibility_services` 必须追加式写入——覆盖会静默禁用用户的其他无障碍服务（输入法等）。`mergeA11yList` 为纯函数并要求单测覆盖（含空串、已含本服务、多服务列表）。
- disconnect 时若 ADB 正在执行命令（adbMutex 占用），`TuningExecutor.disconnect()` 直接 close socket，runCommand 会以 IOException 失败并被 round-1 逻辑清理——可接受，但 UI 文案「已断开」可能出现于命令输出区报错之后，属预期。
- WRITE_SECURE_SETTINGS 授权在 app 卸载重装后失效（Android 行为），重新执行一次 PC 命令即可——向导文案已涵盖。
- SharedPreferences 而非 DataStore：BootReceiver/无障碍服务需要同步读；`datastore-preferences` 依赖当前无使用方，round-2 不引入新用法（是否移除依赖由 lead 决定，避免误伤）。

### 验证方法（无实机预验证下的三层验证）

1. **代码级**：`mergeA11yList` 单测（空/已含/多服务）；`checkPermission` 分支走查。`./gradlew assembleDebug` 编译通过。
2. **探测可见性**：所有探测结果都反映到 adbStatus / SwitchRow 副文案——用户使用时看到的每条状态就是探测输出，无需 logcat。
3. **交付后自验**（用户日常使用即验证）：授权前 AutomationCard 只显示命令；授权后开关可动；连接 → 断开 → 状态栏如实显示自动化/降级原因。

---

## P0-B 可选无障碍服务（前台应用自动应用 Profile）

### 涉及文件

- 新增：`app/src/main/java/com/example/questtuner/tuner/ForegroundProfileService.kt`
- 新增：`app/src/main/res/xml/accessibility_service_config.xml`
- 新增：`app/src/main/res/values/strings.xml`（当前 res 无 strings.xml）
- 修改：`AndroidManifest.xml`

### 设计

- 监听 `TYPE_WINDOW_STATE_CHANGED` 取 `event.packageName`（**只取包名，canRetrieveWindowContent=false**，隐私最小化）。
- 命中 ProfileRepository 中该包的 profile → 应用；**退出该应用（前台变为无 profile 的包）→ 仅回滚我们上一次应用过的**（resetAll），绝不重置用户手动设置的东西之外的状态。设计原则：只撤销自己做过的改动。
- 防抖：XML `notificationTimeout="800"`（系统级节流）+ 代码 `lastApplied` 去重（同一包不重复应用）。
- 服务自身不做 UI、不弹通知；失败静默 + 写本地日志文件（用户可回报问题）。
- `autoApplyProfile` 开关关闭时服务即使被系统拉起也 no-op（开关隔离）。未开启无障碍不影响任何现有功能。

### 具体改动点

**（a）【增】`res/xml/accessibility_service_config.xml`**：
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="false"
    android:description="@string/a11y_service_desc"
    android:notificationTimeout="800" />
```

**（b）【增】`res/values/strings.xml`**：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="a11y_service_desc">仅监听前台应用切换（不读取屏幕内容），用于按应用自动应用调参 Profile。纯本地运行，不上传任何数据。</string>
</resources>
```

**（c）【增】`tuner/ForegroundProfileService.kt`**（全量新文件）：
```kotlin
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
            scope.launch { applyOrRetry(prev, TuningCommands.resetAll()) }
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
```

**（d）修改 `AndroidManifest.xml`**：`</activity>`（:25）之后【增】：
```xml
        <service
            android:name=".tuner.ForegroundProfileService"
            android:exported="true"
            android:label="@string/app_a11y_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
```
strings.xml 补【增】：`<string name="app_a11y_label">QuestTuner 前台调参</string>`
（exported="true" + 系统签名级 BIND 权限保护 = 只有系统可绑定，targetSdk 32 带 intent-filter 必须显式声明 exported。）

### 假设/探测/失败

- A4（自动启用生效）：见 P0-A。
- A9（setprop 全局作用、前台切换重设有效）：round-1 已验证前提；getprop 回读即探测。
- 服务内连接失败（A2/A3 不成立）：`applyOrRetry` 返回 false → `lastAppliedPkg` 复位允许重试 + a11y.log 记录；无崩溃、无弹窗。
- **服务被系统杀死/重启**：onServiceConnected 重建状态；lastAppliedPkg 丢失仅导致多应用一次同值命令（幂等 setprop，无害）。

### 风险与注意事项

- debug.oculus.* 是全局属性：「按应用」语义 = 前台切换时重设全局值（round-1 既有前提）。退出回滚只在我们上一次确实应用过 profile 时发生——不触碰用户手动设置。
- `applyOrRetry` 与 UI 侧 applyProfile 并发：TuningExecutor.adbMutex 串行化（round-1 已有），最坏排队等待，无协议损坏。
- 无障碍服务被系统认为是高敏感权限：UI 的隐私说明文案必须保留「纯本地、不读取屏幕内容」。
- 电池：TYPE_WINDOW_STATE_CHANGED + notificationTimeout=800ms 节流，事件处理为纯内存查表 + 少量 setprop，开销可忽略。

### 验证方法

1. 代码级：服务逻辑走查（防抖/去重/回滚只回滚自己）；`assembleDebug` 编译 + manifest merge 校验（`aapt2 dump xmltree` 或构建输出确认 service 声明）。
2. 交付后自验：开 autoApply → 打开一个已建 profile 的 VR 应用 → 「当前参数」面板（getprop 回读）显示 profile 值；退出后恢复默认。a11y.log 有 applied 记录。开关关掉 → 切换应用无任何命令执行（log 无新记录）。

---

## P1-1 应用选择器（修 BUG-017）

### 涉及文件

- 新增：`app/src/main/java/com/example/questtuner/ui/AppPickerDialog.kt`
- 修改：`ui/TunerApp.kt`（NewProfileCard）、`ui/TunerViewModel.kt`（loadApps 提前可用）

### 设计

- 全屏 Dialog：搜索框（label/包名模糊过滤）+ LazyColumn 应用列表（图标 + 名称 + 包名 + VR 徽标），VR 组排前（AppScanner.isVr 已有）。
- 图标：`Drawable.toBitmap(64,64).asImageBitmap()` 在 `remember` 内按需下采样，避免 BUG-017 提到的 Drawable 常驻内存问题放大。
- 零网络：数据全部来自 PackageManager（AppScanner 本地查询）。
- `loadApps()` 与连接解耦：它是纯 PackageManager 查询，不需要 ADB——选择器打开时若 `state.apps` 为空直接触发加载（未连接也能选应用建 profile，与 round-1 的 BUG-015 修复语义一致）。
- Dialog 独立 window，内部 LazyColumn 与主页 LazyColumn 不存在嵌套滚动问题。

### 具体改动点

**（a）【增】`ui/AppPickerDialog.kt`**（全量新文件）：
```kotlin
package com.example.questtuner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.questtuner.tuner.InstalledApp

/** 本地应用选择器：搜索 + 图标列表，零网络。onSelect 返回包名与标签。 */
@Composable
fun AppPickerDialog(
    apps: List<InstalledApp>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (InstalledApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择应用", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (apps.isEmpty()) {
                    Text("应用列表为空", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRefresh) { Text("加载应用列表") }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppPickerRow(app) { onSelect(app) }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}

@Composable
private fun AppPickerRow(app: InstalledApp, onClick: () -> Unit) {
    // 图标按需下采样（64dp），不放大 Drawable 常驻内存问题
    val bmp = remember(app.icon) {
        runCatching { app.icon?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (bmp != null) {
                Image(bmp, contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (app.isVr) {
                Text("VR", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
```
（依赖 `androidx.core:core-ktx`（已有，build.gradle.kts:50）提供 `Drawable.toBitmap`。）

**（b）修改 `ui/TunerApp.kt` NewProfileCard（:141-191）**：

- :141-146 状态区【增】：
```kotlin
    var pickedLabel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var showPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showPicker) {
        AppPickerDialog(
            apps = state.apps,
            onRefresh = viewModel::loadApps,
            onDismiss = { showPicker = false },
            onSelect = { app ->
                pkg = app.packageName
                pickedLabel = app.label
                showPicker = false
            },
        )
    }
```
- :149-155 包名输入框后【增】按钮：
```kotlin
            OutlinedButton(onClick = {
                if (state.apps.isEmpty()) viewModel.loadApps()
                showPicker = true
            }) { Text("选择应用") }
```
- :172-188 保存按钮 onClick 内【改】 label 逻辑：
```kotlin
                            label = pickedLabel ?: pkg.ifBlank { "全局" },
```

**（c）修改 `ui/TunerViewModel.kt` loadApps（:78-85）**：无必须改动（已可独立调用）；仅确认它不依赖连接状态——现状即如此（connect 成功后调用只是触发时机之一）。

### 风险与注意事项

- `toBitmap(64,64)` 对无图标/异常 Drawable 用 runCatching 兜底（round-1 BUG-010 同类竞态思维：列表数据加载与应用卸载并发）。
- Dialog `usePlatformDefaultWidth = false` 全屏在 Quest 2D 面板上体验更好；若发现标题被状态栏遮挡，Wang 可改用 Scaffold + TopAppBar 微调（视觉问题，不阻塞）。
- 搜索过滤在 `remember(apps, query)` 中做，列表大（数百 app）时每次输入全量过滤——一次性内存过滤可接受，不需要 Flow 优化。

### 验证方法

1. 代码级：编译；走查空列表/搜索无结果路径（filtered 为空显示空列表，不崩溃）。
2. 交付后自验：未连接状态 → 新建 Profile → 选择应用 → 列表出现、搜索可过滤、选中后包名与标签预填（label 显示应用名而非包名）→ 保存 → ProfileRow 显示应用名。VR 应用带 VR 徽标且排前。

---

## P1-2 自更新（GitHub Releases → 校验 → PackageInstaller）

### 涉及文件

- 新增：`app/src/main/java/com/example/questtuner/update/SelfUpdater.kt`
- 新增：`app/src/main/java/com/example/questtuner/update/InstallResultReceiver.kt`
- 新增：`app/src/main/java/com/example/questtuner/ui/UpdateCard.kt`
- 修改：`AndroidManifest.xml`、`ui/TunerViewModel.kt`、`ui/TunerApp.kt`（已在前文挂载 item）、`app/build.gradle.kts`（release 签名，见 P3）

### 设计

- 流程：检查（GitHub API，HTTPS）→ 下载到 `getExternalFilesDir` → **四重校验**（可解析 / 包名一致 / 签名证书一致 / 版本更高防降级）→ PackageInstaller 会话安装 → canRequestPackageInstalls 门禁 → 系统确认页（A6）→ 结果回传 UI。
- **失败回滚语义**：PackageInstaller 是原地更新，失败/取消 = 旧版本原样保留（无显式回滚动作需要）；下载文件在校验失败/安装终态后删除。校验失败绝不进入安装。
- 不做 QGO 的 cleartext NSC / FileProvider external-path / 上报——GitHub API 是 HTTPS，下载文件通过 PackageInstaller stream 直接写入会话，不经 FileProvider。
- 默认不自动检查更新（网络隐私默认关闭）；UpdateCard 只有手动「检查更新」按钮。

### 具体改动点

**（a）【增】`update/SelfUpdater.kt`**（全量新文件）：
```kotlin
package com.example.questtuner.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SelfUpdater {

    // TODO(lead): GitHub 仓库坐标，发布前填入；REPO 未配置时 checkLatest 直接返回「未配置」
    private const val REPO = "OWNER/QUESTTUNER"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class ReleaseInfo(val tag: String, val url: String, val notes: String, val size: Long)

    /** 安装结果通道（receiver 无 VM 引用，经此流转给 UI 观察） */
    private val _installResult = MutableStateFlow<String?>(null)
    val installResult: StateFlow<String?> = _installResult

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    // ---- 1) 检查 ----
    suspend fun checkLatest(context: Context): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        if (REPO.startsWith("OWNER/")) return@withContext Result.failure(IllegalStateException("更新源未配置"))
        runCatching {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "QuestTuner")   // GitHub API 必需
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                val tag = o.optString("tag_name")
                if (tag.isBlank()) return@runCatching null
                val asset = o.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                } ?: return@runCatching null
                val remote = tag.removePrefix("v")
                if (!versionGreaterThan(remote, currentVersionName(context))) {
                    return@runCatching null   // 已是最新
                }
                ReleaseInfo(
                    tag = tag,
                    url = asset.optString("browser_download_url"),
                    notes = o.optString("body").take(2000),
                    size = asset.optLong("size"),
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---- 2) 下载（带进度回调） ----
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            dest.parentFile?.mkdirs()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                dest.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
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

    fun updateApk(context: Context, tag: String): File =
        File(File(context.getExternalFilesDir(null), "update"), "questtuner-$tag.apk")

    // ---- 3) 四重校验（任一失败拒绝安装并删文件） ----
    fun verifyApk(context: Context, f: File): Result<Unit> = runCatching {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("下载的 APK 无法解析（可能损坏）")
        check(archive.packageName == context.packageName) { "包名不匹配: ${archive.packageName}" }
        val newSigs = archive.signingInfo?.apkContentsSigners ?: error("APK 无签名信息")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val oldSigs = installed.signingInfo?.apkContentsSigners ?: error("无法读取已安装签名")
        check(newSigs.size == oldSigs.size &&
            newSigs.zip(oldSigs).all { (a, b) -> a.toByteArray().contentEquals(b.toByteArray()) }) {
            "签名证书不一致，拒绝安装"
        }
        check(versionGreaterThan(archive.versionName ?: "", currentVersionName(context))) {
            "版本不高于当前，拒绝降级安装"
        }
    }

    // ---- 4) 安装（含 canRequestPackageInstalls 门禁） ----
    // 假设A6：Quest 弹出系统安装确认界面
    // 探测：InstallResultReceiver 收到的 EXTRA_STATUS
    // 失败：旧版本原地保留（天然回滚），UI 提示 PC adb install 备选
    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        // 门禁：未允许「安装未知应用」时引导到系统设置页（canRequestPackageInstalls=false 时调用）
        val i = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val session = installer.openSession(params)
        session.openWrite("questtuner", 0, -1).use { out ->
            apk.inputStream().use { it.copyTo(out) }
            out.flush()
        }
        val intent = Intent(context, InstallResultReceiver::class.java)
            .putExtra("apk_path", apk.absolutePath)
        // PackageInstaller 要求 mutable PendingIntent（系统会填入 status 与确认 Intent）
        val pending = android.app.PendingIntent.getBroadcast(
            context, 1001, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
        )
        session.commit(pending.intentSender)
        session.close()
    }

    fun notifyInstallResult(success: Boolean, message: String?) {
        _installResult.value = if (success) "更新安装成功" else "安装失败: ${message ?: "未知错误"}"
    }

    fun consumeInstallResult() { _installResult.value = null }

    // ---- 版本比较（纯函数，单测覆盖）："0.3.0" > "0.2.9" ----
    fun versionGreaterThan(a: String, b: String): Boolean {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
```

**（b）【增】`update/InstallResultReceiver.kt`**：
```kotlin
package com.example.questtuner.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val apkPath = intent.getStringExtra("apk_path")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 假设A6：拉起系统安装确认页；用户取消 → 旧版本保留
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                apkPath?.let { File(it).delete() }   // 清理下载文件
                SelfUpdater.notifyInstallResult(true, null)
            }
            else -> {
                apkPath?.let { File(it).delete() }
                SelfUpdater.notifyInstallResult(
                    false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }
}
```

**（c）修改 `AndroidManifest.xml`**：权限区（:5 附近）【增】：
```xml
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```
application 内【增】（与 P0-B 的 service 并列）：
```xml
        <receiver android:name=".update.InstallResultReceiver" android:exported="false" />
```

**（d）【增】`ui/UpdateCard.kt`**（全量新文件）：
```kotlin
package com.example.questtuner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.questtuner.update.SelfUpdater

@Composable
fun UpdateCard(state: UiState, viewModel: TunerViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("更新", style = MaterialTheme.typography.titleMedium)
            Text("当前 ${state.updateStatus.ifEmpty { "手动检查更新（GitHub Releases，HTTPS）" }}",
                style = MaterialTheme.typography.bodySmall)
            if (state.updateProgress >= 0) {
                LinearProgressIndicator(
                    progress = { state.updateProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::checkUpdate) { Text("检查更新") }
                if (state.updateAvailable) {
                    Button(onClick = viewModel::downloadAndInstall) { Text("下载并安装") }
                }
            }
        }
    }
}
```
（`state.updateAvailable` / `updateStatus` / `updateProgress` 为 UiState 新增字段，见 (e)。）

**（e）修改 `ui/TunerViewModel.kt`**：UiState 再【增】三个字段：
```kotlin
    val updateAvailable: Boolean = false,
    val updateStatus: String = "",
    val updateProgress: Int = -1,     // -1 无任务；0..100 下载进度
```
类成员【增】：
```kotlin
    private var pendingRelease: SelfUpdater.ReleaseInfo? = null
```
init 中【增】观察安装结果：
```kotlin
            viewModelScope.launch {
                SelfUpdater.installResult.collect { r ->
                    if (r != null) {
                        _state.value = _state.value.copy(
                            updateStatus = r, updateProgress = -1, updateAvailable = false)
                        SelfUpdater.consumeInstallResult()
                    }
                }
            }
```
【增】函数：
```kotlin
    fun checkUpdate() {
        viewModelScope.launch {
            _state.value = _state.value.copy(updateStatus = "检查中…")
            val r = SelfUpdater.checkLatest(getApplication())
            r.onSuccess { info ->
                if (info == null) {
                    pendingRelease = null
                    _state.value = _state.value.copy(updateStatus = "已是最新版本")
                } else {
                    pendingRelease = info
                    _state.value = _state.value.copy(
                        updateAvailable = true,
                        updateStatus = "新版本 ${info.tag} 可用（${info.size / 1024}KB）",
                    )
                }
            }.onFailure {
                _state.value = _state.value.copy(updateStatus = "检查失败: ${it.message}")
            }
        }
    }

    fun downloadAndInstall() {
        val info = pendingRelease ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            // 门禁：未允许安装未知应用 → 引导系统设置（用户返回后需重新点下载安装）
            if (!SelfUpdater.canRequestInstalls(ctx)) {
                SelfUpdater.requestInstallPermission(ctx)
                _state.value = _state.value.copy(
                    updateStatus = "请先允许本应用安装未知应用，返回后重试",
                )
                return@launch
            }
            _state.value = _state.value.copy(updateStatus = "下载中…", updateProgress = 0)
            val apk = SelfUpdater.updateApk(ctx, info.tag)
            SelfUpdater.download(info.url, apk) { p ->
                _state.value = _state.value.copy(updateProgress = p)
            }.onSuccess { f ->
                // 四重校验失败 → 删文件 + 状态提示，绝不进入安装
                SelfUpdater.verifyApk(ctx, f).onSuccess {
                    _state.value = _state.value.copy(
                        updateStatus = "校验通过，等待系统确认…", updateProgress = -1)
                    SelfUpdater.install(ctx, f)
                }.onFailure { e ->
                    f.delete()
                    _state.value = _state.value.copy(
                        updateStatus = "校验失败，已取消: ${e.message}", updateProgress = -1)
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    updateStatus = "下载失败: ${e.message}", updateProgress = -1)
            }
        }
    }
```

### 假设/探测/失败

- A6 系统安装确认页：探测 = InstallResultReceiver 的 EXTRA_STATUS 终态；失败 = 旧版本原地保留 + 状态文案给 PC `adb install` 备选路径（updateStatus 文案可加；当前版本已含失败原因展示）。
- GitHub API 可达性/限流：HTTP 状态码非 200 → 「检查失败: HTTP xxx」，无重试轰炸。
- 签名一致性：依赖 release 构建使用与已安装版本相同的签名（P3 中 release 沿用 debug keystore 签名保证这一点；若未来换 keystore，自更新将因签名校验失败而拒绝——这是设计行为，防止换签攻击）。

### 风险与注意事项

- `REPO` 常量是占位符，**发布前必须由 lead 填入真实 GitHub 仓库坐标**；未配置时 checkLatest 明确报「更新源未配置」而不是假装无更新。
- `getParcelableExtra` 在 API 33+ 有类型安全新 API，但 targetSdk 32 下旧 API 正常（未来升 targetSdk 时改 `getParcelableExtra(key, Intent::class.java)`）。
- 下载中断（进程被杀）：dest 文件残留 → 下次下载同 tag 覆盖写，无累积（文件名固定按 tag）。`update/` 目录可在 install 成功/失败后统一清理（receiver 已删）。
- 不做自动检查（默认关闭原则）；不引入 OkHttp/Coil 等新依赖，HttpURLConnection 足够。
- REQUEST_INSTALL_PACKAGES 权限只影响「安装未知应用」引导门禁，是 PackageInstaller 会话提交的必要条件，属标准做法（与 QGO 的 FileProvider external-path 方案无关）。

### 验证方法

1. 代码级：`versionGreaterThan` 单测（等长/不等长/含非数字段/v 前缀）；`verifyApk` 的四个失败分支走查；`assembleDebug`/`assembleRelease` 编译。
2. 构造测试（PC 侧，无需头显预验证）：本地起 GitHub API mock（或改 API 常量指向局域网 HTTPS 不现实——简化为：PC 上用单元测试覆盖 JSON 解析函数逻辑；实机流程留给交付后自验）。
3. 交付后自验：检查更新 → 有新版本 → 下载进度条 → 校验通过 → 系统确认页（A6 验证点）→ 安装成功状态；篡改场景（用不同签名的 APK 替换 Release 资产）→ 校验失败拒绝安装（安全行为确认）。

---

## P2-1 OVR metrics 性能叠加层开关

### 涉及文件

- 新增：`app/src/main/java/com/example/questtuner/tuner/OvrMetrics.kt`
- 修改：`ui/AutomationCard.kt`（本节给出 OvrMetricsSection 实现）

### 设计

- QGO 证据：显式广播到 `ComponentName("com.oculus.ovrmonitormetricsservice", "...SettingsBroadcastReceiver")`，action `com.oculus.ovrmonitormetricsservice.` + suffix（ENABLE_OVERLAY / ENABLE_GRAPH / ENABLE_STAT），extras 类型见 String/Float/Int/Double/Boolean。**具体 receiver 类名全称与 extras 键名未验证（A7）**。
- 策略：
  1. **确定可探测的部分**：目标包是否安装（`pm.getPackageInfo`）——不安装则整区块禁用并说明；
  2. **不可探测的部分**：广播无回执，效果确认不了 → UI 文案如实说「已发送，效果请直接观察叠加层」；
  3. 所有常量（类名/action/extras 键名）集中在一处 + `@JvmStatic` 便于将来一键修正；默认尝试的键名集合按 QGO 字节码线索 + 公开文档惯例，全部标注假设；
  4. 提供「重新发送」按钮（幂等），并把 PC 验证脚本写进本文档供 lead/用户后续校准。

### 具体改动点

**（a）【增】`tuner/OvrMetrics.kt`**（全量新文件）：
```kotlin
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
```

**（b）`ui/AutomationCard.kt`【增】section**（在 SwitchRow 组之后调用）：
```kotlin
@Composable
private fun OvrMetricsSection(state: UiState, viewModel: TunerViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text("OVR Metrics 性能叠加层", style = MaterialTheme.typography.bodyMedium)
    if (!OvrMetrics.isAvailable(ctx)) {
        // 确定探测失败：目标包不存在 → 整块禁用并说明
        Text("未检测到 OVR Metrics 服务（com.oculus.ovrmonitormetricsservice），功能不可用。",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    // 假设A7：键名未验证；文案如实告知效果无法在应用内确认
    Text("通过系统广播控制（与 QGO 同源）。已发送的开关无法在应用内确认效果，请直接观察头显内的叠加层。",
        style = MaterialTheme.typography.bodySmall)
    SwitchRow("Overlay 叠加层", "广播开关", state.metricsOverlay, true) {
        viewModel.setMetricsOverlay(it)
    }
    SwitchRow("Graph 曲线图", "广播开关", state.metricsGraph, true) {
        viewModel.setMetricsGraph(it)
    }
    SwitchRow("Stat 统计面板", "广播开关", state.metricsStat, true) {
        viewModel.setMetricsStat(it)
    }
    OutlinedButton(onClick = { viewModel.resendMetrics() }) { Text("重新发送全部开关") }
}
```
配套 TunerPrefs【增】三个布尔键（metricsOverlay/metricsGraph/metricsStat，默认 false）；UiState【增】三个镜像字段；TunerViewModel【增】：
```kotlin
    fun setMetricsOverlay(v: Boolean) { prefs.metricsOverlay = v; resendMetrics() }
    fun setMetricsGraph(v: Boolean) { prefs.metricsGraph = v; resendMetrics() }
    fun setMetricsStat(v: Boolean) { prefs.metricsStat = v; resendMetrics() }
    fun resendMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            OvrMetrics.applyState(
                getApplication(), prefs.metricsOverlay, prefs.metricsGraph, prefs.metricsStat)
        }
    }
```
（TunerPrefs 的三个键同样默认 false。）

### 假设/探测/失败 + 验证脚本（A7 校准手段）

- 探测层次①（app 内，确定）：`isAvailable` 包安装检测 → 不在则禁用整块。
- 探测层次②（app 内，不可能）：广播投递无回执，效果无法确认 → UI 文案如实声明。
- **PC 验证脚本**（交付后由 lead/用户执行，用于校准三个 TODO 常量）：
```bash
# 1) 确认 receiver 真实类名（修正 TARGET_CLS 的依据）
adb shell dumpsys package com.oculus.ovrmonitormetricsservice | grep -B2 -A6 Receiver

# 2) 直接用 am broadcast 试验（观察头显叠加层是否变化）
adb shell am broadcast \
  -n com.oculus.ovrmonitormetricsservice/.SettingsBroadcastReceiver \
  -a com.oculus.ovrmonitormetricsservice.ENABLE_OVERLAY --ez enabled true

# 3) 若 "enabled" 键无效，逐一尝试候选键名（修正 EXTRA_ENABLED 的依据）：
#    --ez value true / --ez state true / --ez overlay_enabled true
#    以及不带 extras 直接发 action（部分版本仅以 action 触发开关切换）
adb shell am broadcast -a com.oculus.ovrmonitormetricsservice.ENABLE_OVERLAY \
  -n com.oculus.ovrmonitormetricsservice/.SettingsBroadcastReceiver
```
校准结果只改 OvrMetrics.kt 顶部三个常量，无其他代码变动。

### 风险与注意事项

- 显式广播到错误组件 = 静默丢弃，无崩溃风险；开关状态只是「意图记录」，可能与实际叠加层状态不同步——文案已声明。
- 广播动作是幂等开关（每次发送当前期望状态，非 toggle 语义）——按 QGO 线索 extras 携带布尔值；若验证发现实际是 toggle 语义（无 extras），`resendMetrics` 的「重新发送」按钮即为手动补救手段。
- 广播目标是系统组件、无自定义权限要求（QGO 亦如此）；不涉及任何数据外发。

### 验证方法

1. 代码级：编译；`isAvailable` 两分支走查；常量集中性检查（grep 确认 action/键名只出现在 OvrMetrics.kt）。
2. 交付后自验：执行上方 PC 脚本校准常量 → app 内开 Overlay 开关 → 头显出现叠加层（A7 的最终确认）。

---

## P2-2 Private DNS / 首次配置向导 / 开机恢复默认

### 涉及文件

- 新增：`tuner/PrivateDns.kt`、`tuner/BootReceiver.kt`、`ui/SetupWizard.kt`
- 修改：`AndroidManifest.xml`、`ui/AutomationCard.kt`（PrivateDnsSection）、`ui/TunerApp.kt`（挂载向导）、`ui/TunerViewModel.kt`

### 2a. Private DNS（可选功能，A8）

**设计**：写入标准 AOSP 两键（`private_dns_mode` = hostname/off + `private_dns_specifier` = 主机名）；写后回读探测；失败自动还原旧值；UI 带风险提示（错误的 DNS 主机名会导致联网异常）与快速「关闭」按钮。需要 WRITE_SECURE_SETTINGS（同一 A1 门禁）。

**【增】`tuner/PrivateDns.kt`**（全量新文件）：
```kotlin
package com.example.questtuner.tuner

import android.content.Context
import android.provider.Settings

/**
 * Private DNS 控制（QGO 同源：Settings.Global 两键）。
 * 假设A8：Quest 遵循 AOSP private_dns_mode / private_dns_specifier 行为。
 * 探测：写入后回读两键。
 * 失败：还原写入前旧值，返回错误信息。
 */
object PrivateDns {
    private const val MODE = "private_dns_mode"
    private const val SPECIFIER = "private_dns_specifier"

    sealed class DnsResult {
        object Ok : DnsResult()
        object NoPermission : DnsResult()
        data class Rejected(val detail: String) : DnsResult()
    }

    fun currentMode(context: Context): String? =
        Settings.Global.getString(context.contentResolver, MODE)

    fun currentSpecifier(context: Context): String? =
        Settings.Global.getString(context.contentResolver, SPECIFIER)

    fun setHostname(context: Context, host: String): DnsResult {
        if (!SecureAutomation.hasWriteSecureSettings(context)) return DnsResult.NoPermission
        val cr = context.contentResolver
        val oldMode = currentMode(context)
        val oldSpec = currentSpecifier(context)
        return try {
            Settings.Global.putString(cr, MODE, "hostname")
            Settings.Global.putString(cr, SPECIFIER, host)
            // 回读探测
            val m = Settings.Global.getString(cr, MODE)
            val s = Settings.Global.getString(cr, SPECIFIER)
            if (m == "hostname" && s == host) DnsResult.Ok
            else {
                // 失败自动还原，不留半套状态
                Settings.Global.putString(cr, MODE, oldMode ?: "off")
                Settings.Global.putString(cr, SPECIFIER, oldSpec ?: "")
                DnsResult.Rejected("回读不一致（mode=$m）")
            }
        } catch (e: Exception) {
            runCatching {
                Settings.Global.putString(cr, MODE, oldMode ?: "off")
                Settings.Global.putString(cr, SPECIFIER, oldSpec ?: "")
            }
            DnsResult.Rejected(e.message)
        }
    }

    fun off(context: Context): DnsResult {
        if (!SecureAutomation.hasWriteSecureSettings(context)) return DnsResult.NoPermission
        return try {
            Settings.Global.putString(context.contentResolver, MODE, "off")
            if (Settings.Global.getString(context.contentResolver, MODE) == "off") DnsResult.Ok
            else DnsResult.Rejected("回读不一致")
        } catch (e: Exception) {
            DnsResult.Rejected(e.message)
        }
    }
}
```

**`ui/AutomationCard.kt`【增】section**：
```kotlin
@Composable
private fun PrivateDnsSection(state: UiState, viewModel: TunerViewModel) {
    var host by remember { mutableStateOf("") }
    Text("Private DNS（可选）", style = MaterialTheme.typography.bodyMedium)
    if (!state.hasSecureGrant) {
        Text("需一次性授权后可用（见上方命令）。", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("当前模式: ${state.privateDnsMode ?: "未知"}。错误的 DNS 主机名会导致联网异常。",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            placeholder = { Text("dns.example.com") },
            singleLine = true, modifier = Modifier.weight(1f),
        )
        Button(onClick = { viewModel.setPrivateDns(host) }, enabled = host.isNotBlank()) {
            Text("应用")
        }
        OutlinedButton(onClick = viewModel::privateDnsOff) { Text("关闭") }
    }
}
```
UiState【增】`val privateDnsMode: String? = null`；TunerViewModel【增】：
```kotlin
    fun setPrivateDns(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = PrivateDns.setHostname(getApplication(), host)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    privateDnsMode = PrivateDns.currentMode(getApplication()),
                    adbStatus = when (r) {
                        PrivateDns.DnsResult.Ok -> "Private DNS 已设置为 $host"
                        is PrivateDns.DnsResult.Rejected -> "Private DNS 设置失败已还原: ${r.detail}"
                        PrivateDns.DnsResult.NoPermission -> "未授权，Private DNS 不可用"
                    },
                )
            }
        }
    }

    fun privateDnsOff() {
        viewModelScope.launch(Dispatchers.IO) {
            PrivateDns.off(getApplication())
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    privateDnsMode = PrivateDns.currentMode(getApplication()),
                    adbStatus = "Private DNS 已关闭",
                )
            }
        }
    }
```
（init 中补 `privateDnsMode = PrivateDns.currentMode(app)`。）

**风险**：错误主机名 = 设备联网异常 → UI 警告文案 + 「关闭」一键恢复；回读失败自动还原旧值，不留半套状态。写 private_dns_specifier 空串的还原路径取自写入前快照。

### 2b. 首次配置向导

**【增】`ui/SetupWizard.kt`**（全量新文件，单 Dialog 两步）：
```kotlin
package com.example.questtuner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.questtuner.tuner.SecureAutomation

/** 首次向导：说明调参机制 + 一次性授权命令 + 自动化开关（全部默认关，用户主动勾选）。 */
@Composable
fun SetupWizard(state: UiState, viewModel: TunerViewModel) {
    var step by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (step == 1) "欢迎使用 QuestTuner" else "自动化选项（可选）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 1) {
                    Text("本应用通过设备本机 ADB（127.0.0.1:5555）执行 setprop 调节 Quest 3 参数。")
                    Text("使用前请开启无线调试（系统设置 → 开发者选项），或用 PC 执行一次性授权命令解锁下方自动化功能：")
                    val clip = LocalClipboardManager.current
                    Text(SecureAutomation.GRANT_CMD, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        clip.setText(AnnotatedString(SecureAutomation.GRANT_CMD))
                    }) { Text("复制命令") }
                    Text("不授权也能用：手动开启无线调试即可连接。所有调参均在设备本地完成，无数据上传。",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("以下自动化默认关闭，按需开启（随时可在「自动化与增强」中更改）：")
                    // 复用 AutomationCard 的开关语义，向导内直接勾选
                    WizardSwitch("连接时自动开启无线调试", state.autoWifiAdb) { viewModel.setAutoWifiAdb(it) }
                    WizardSwitch("自动启用无障碍服务", state.autoA11y) { viewModel.setAutoA11y(it) }
                    WizardSwitch("按前台应用自动应用 Profile", state.autoApply) { viewModel.setAutoApply(it) }
                    WizardSwitch("开机恢复默认参数", state.restoreOnBoot) { viewModel.setRestoreOnBoot(it) }
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                TextButton(onClick = { step = 2 }) { Text("下一步") }
            } else {
                TextButton(onClick = viewModel::dismissWizard) { Text("开始使用") }
            }
        },
    )
}

@Composable
private fun WizardSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.alignment.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
```
**`ui/TunerApp.kt`【改】**：`TunerApp` composable 根部（MaterialTheme 内、LazyColumn 前）【增】：
```kotlin
        if (state.wizardVisible) {
            SetupWizard(state, viewModel)
        }
```

**风险**：向导勾选 autoA11y 时若无授权 → setAutoA11y 已有的降级文案会写入 adbStatus，但被 AlertDialog 遮挡 → 接受（用户进入主界面后状态栏可见）；如 Wang 有余量，可在向导第二步顶部展示 `state.hasSecureGrant` 提示（一行 Text）。

### 2c. 开机恢复默认（依赖 P0-A 通道，A5）

**【增】`tuner/BootReceiver.kt`**（全量新文件）：
```kotlin
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
```
**`AndroidManifest.xml`【增】**：
```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```
application 内：
```xml
        <receiver android:name=".tuner.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

**风险与注意事项**：
- best-effort 语义必须写进 UI 开关文案（AutomationCard 的「开机恢复默认参数」副文案已含「需无线调试可用」；Wang 可再加「尽力而为」措辞）。
- BootReceiver 中 resetAll 成功后立即 disconnect 并回滚无线调试——不留常驻连接。
- Quest 3 是否在开机时向第三方 app 投递 BOOT_COMPLETED：属平台行为，**假设层面与 A5 合并**；探测 = boot.log 是否出现记录（无记录 = 广播未达，开关功能在此固件上自然失效，无副作用）。

**验证方法（P2-2 全部）**：
1. 代码级：PrivateDns 状态机走查（成功/回读失败还原/异常还原三路径）单测覆盖（可用 robolectric 或将 Settings 交互抽象后测纯逻辑——Wang 可选实现方式）；编译 + manifest merge 检查。
2. 交付后自验：Private DNS 设一个假主机名 → 回读模式 hostname；点「关闭」→ off；网络恢复正常（A8 确认）。开机恢复：开开关 → 重启 → getprop 显示默认值 + boot.log 有记录（A5 确认）。向导：首次启动出现、勾选持久化、二次启动不再出现。

---

## P3 Release 工程化（R8 + 移除 tooling 残留 + 签名）

### 涉及文件

- 修改：`app/build.gradle.kts`
- 新增：`app/proguard-rules.pro`

### 具体改动点

**（a）`app/build.gradle.kts:15-16`【改】**：
```kotlin
        versionCode = 2
        versionName = "0.2.0"
```

**（b）`app/build.gradle.kts:25-27` release 块【改】**：
```kotlin
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // P1-2 自更新要求新旧版本签名一致；暂复用 debug keystore。
            // TODO(lead): 正式对外发布前更换专用 release keystore（换签会导致已装用户自更新校验失败，需一次性迁移策略）
            signingConfig = signingConfigs.getByName("debug")
        }
```

**（c）`app/build.gradle.kts:46`【删】**：
```kotlin
    implementation("androidx.compose.ui:ui-tooling-preview")   // 删除：工程无 @Preview，PreviewActivity 等 tooling 残留即来自此依赖
```
（如后续要用 IDE 预览，加 `debugImplementation("androidx.compose.ui:ui-tooling")` 即可，不影响 release。）

**（d）【增】`app/proguard-rules.pro`**：
```
# QuestTuner R8 规则
# 工程 未使用反射；manifest 引用组件（MainActivity/ForegroundProfileService/
# BootReceiver/InstallResultReceiver）由 AGP 自动 keep；
# coroutines / compose 规则内置于依赖自身。当前无需额外 keep。
```

### 风险与注意事项

- R8 最大风险是误删仅被 manifest/资源引用的类——AGP 自动处理 manifest 组件与 `@xml/accessibility_service_config`（meta-data resource 引用会被资源收缩器识别）；无需手动 keep。
- shrinkResources + 无动态资源查找（无 getIdentifier）→ 安全。
- **release 换用 debug keystore 签名是临时决定**（自更新签名一致性要求换签必须有迁移方案）；已在 TODO 中标注由 lead 决策，不要静默长期沿用。
- 开启 minify 后 P1-2 的签名校验不受影响（签名是 APK 级，与代码混淆无关）。
- 注意：round-2 全部功能先在 debug 验证（不混淆），最后一步才开 R8——排查混淆问题的成本远高于最后开启。

### 验证方法

1. `./gradlew assembleRelease` 成功；对比 APK 体积（预期显著缩小）。
2. `aapt2 dump badging app-release.apk` / apkanalyzer 检查：无 `androidx.compose.ui.tooling.PreviewActivity`；versionCode/Name 正确。
3. 交付后自验：安装 release APK → 完整回归清单（下节）——R8 问题在此阶段暴露，若有 ClassNotFound/资源缺失，回 P3 增加 keep 规则。

---

## 明确不做（lead 已定）

| 项 | 理由 |
|---|---|
| cleartext NSC（base-config cleartextTrafficPermitted=true） | 纯 HTTP 明文放行是安全劣化；本方案全部网络仅 GitHub HTTPS，无需求 |
| FileProvider external-path "." | 暴露整个外部存储的常见漏洞模式；自更新用 PackageInstaller 会话流直写，不经 FileProvider |
| deviceId / 包名上报第三方 API | 隐私红线；本方案零遥测（a11y.log/boot.log 仅本地） |
| MANAGE_EXTERNAL_STORAGE | 全文件访问权限，远超需求；本方案只写 app 专属目录（getExternalFilesDir） |

---

## 全局注意事项（Wang 实施总纲）

1. **分支**：`fix/round-2`；只实施本方案列出的文件，不扩散。
2. **顺序**：按「总览」后的 8 步实施，每步独立可编译、可 review；R8 必须最后。
3. **默认值纪律**：所有新开关默认 false；所有网络行为（仅 GitHub API/下载）只在用户显式点击时发生——这两条是 lead 约束的硬性要求，review 时逐项核对。
4. **降级优先**：每个自动化路径先想「探测失败怎么办」再写代码——降级目标永远是 round-1 的手动流程，绝不让失败变成崩溃或死等。
5. **假设不写成事实**：代码注释中的「假设/探测/失败」三段式保留（SecureAutomation/OvrMetrics/PrivateDns/BootReceiver 已写好，Wang 不要删）；TODO(验证) 标记待用户/lead 校准的常量只集中在 OvrMetrics.kt 与 SelfUpdater.kt 的 REPO。
6. **单测**（`app/src/test/`，org.json 纯 JVM 是 stub——SelfUpdater 的 JSON 解析如需单测放 androidTest 或加 `testImplementation("org.json:json:20240303")`）：`mergeA11yList`、`versionGreaterThan`、PrivateDns 状态机（抽象后）、TunerPrefs 读写。
7. **不改动**：adb/ 包（round-1 成果）、TuningCommands（本方案复用）、调参命令路径——P0-A 明确不碰 setprop 通道。
8. **交付后自验清单**（用户日常使用即验证，全部通过才可关闭 round-2）：
   1. 首次启动出现向导；不勾任何开关 → 行为与 round-1 完全一致（回归保证）
   2. 未授权状态：自动化区只读 + 命令可复制；连接走手动路径正常
   3. PC 执行授权命令 → 重进 app：开关可动（A1 确认）
   4. 开自动无线调试 → 连接成功 → 断开后无线调试被恢复；手动开的无线调试在断开后不被关闭（回滚边界确认）
   5. 开自动无障碍 → 服务运行状态如实显示；开关关闭 → 服务从系统无障碍列表移除（A4 确认）
   6. 开自动应用 → 进出有 profile 的 VR 应用 → 参数应用/回滚；a11y.log 有记录
   7. 应用选择器：搜索/选择/预填 label；未连接可用
   8. 检查更新 → 下载进度 → 校验 → 系统确认页 → 安装成功（A6 确认）；换签 APK 被拒绝
   9. OVR Metrics：未装目标包区块禁用；装了则开关可发（A7 常量按 PC 脚本校准）
   10. Private DNS 设置/关闭/失败还原（A8 确认）
   11. 开机恢复：重启后参数默认 + boot.log 记录（A5 确认）
   12. release APK：体积缩小、无 tooling 残留、全部功能回归
