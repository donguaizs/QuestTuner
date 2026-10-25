package com.example.questtuner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.questtuner.tuner.AppProfile
import com.example.questtuner.tuner.AppScanner
import com.example.questtuner.tuner.InstalledApp
import com.example.questtuner.tuner.PrivateDns
import com.example.questtuner.tuner.OvrMetrics
import com.example.questtuner.tuner.ProfileRepository
import com.example.questtuner.tuner.SecureAutomation
import com.example.questtuner.tuner.TuningCommands
import com.example.questtuner.tuner.TuningExecutor
import com.example.questtuner.tuner.TunerPrefs
import com.example.questtuner.update.SelfUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val adbStatus: String = "未连接",
    val currentStatus: String = "",
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = false,      // O4：应用列表加载态（防重复 IPC）
    val profiles: List<AppProfile> = emptyList(),
    val lastCommandOutput: String = "",
    // ---- round-2 自动化与增强 ----
    val hasSecureGrant: Boolean = false,   // A1 探测结果
    val autoWifiAdb: Boolean = false,
    val autoA11y: Boolean = false,
    val autoApply: Boolean = false,        // P0-B
    val restoreOnBoot: Boolean = false,    // P2-2
    val a11yRunning: Boolean = false,      // A4 行为级探测结果
    val wizardVisible: Boolean = false,    // P2-2 首次向导
    val metricsOverlay: Boolean = false,   // P2-1
    val metricsGraph: Boolean = false,
    val metricsStat: Boolean = false,
    val privateDnsMode: String? = null,    // P2-2
    // ---- P1-2 自更新 ----
    val updateAvailable: Boolean = false,
    val updateStatus: String = "",
    val updateChecking: Boolean = false,   // O7：检查更新防重入
    // ---- round-6 ----
    val editingProfile: AppProfile? = null,   // BUG-041：非 null = NewProfileCard 编辑模式
)

class TunerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository.of(app.filesDir)
    private val prefs = TunerPrefs.get(app)
    // R4-O3：wizardVisible 构造时同步读 prefs（SP 首读开销可忽略），消除「主界面闪现再弹向导」
    private val _state = MutableStateFlow(UiState(wizardVisible = !prefs.firstRunDone))
    val state: StateFlow<UiState> = _state
    private var pendingRelease: SelfUpdater.ReleaseInfo? = null
    /** BUG-026：connecting 态的连接协程，支持取消 */
    private var connectJob: kotlinx.coroutines.Job? = null
    /** BUG-034：下载进度独立通道——高频发射不再触发全树重组（UpdateCard 局部 collect） */
    private val _updateProgress = MutableStateFlow(-1)
    val updateProgress: StateFlow<Int> = _updateProgress

    init {
        viewModelScope.launch {
            val ps = withContext(Dispatchers.IO) { repo.profiles() }
            _state.value = _state.value.copy(
                profiles = ps,
                autoWifiAdb = prefs.autoWifiAdb,
                autoA11y = prefs.autoA11y,
                autoApply = prefs.autoApplyProfile,
                restoreOnBoot = prefs.restoreOnBoot,
                metricsOverlay = prefs.metricsOverlay,
                metricsGraph = prefs.metricsGraph,
                metricsStat = prefs.metricsStat,
            )
        }
        refreshCapabilities()   // BUG-029：取代原 init 一次性能力快照
        viewModelScope.launch(Dispatchers.IO) {
            // BUG-032：启动对账——上次会话残留的「我们开的无线调试」回滚（保守：只回滚自己标记的）
            SecureAutomation.reconcileWifiAdbOnStartup(getApplication())
        }
        viewModelScope.launch {
            SelfUpdater.installResult.collect { r ->
                if (r != null) {
                    _state.value = _state.value.copy(
                        updateStatus = r, updateAvailable = false)
                    _updateProgress.value = -1
                    SelfUpdater.consumeInstallResult()
                }
            }
        }
        // BUG-027 / O10：executor 连接实态同步 UI——服务侧重连/意外断开都不再脱节
        viewModelScope.launch {
            TuningExecutor.connectionState.collect { live ->
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
    }

    /**
     * BUG-029：能力快照重探测（onStart / 向导步骤切换 / init 三处触发）。
     * BUG-030 对账：autoA11y 开关 ON 且已授权但服务未运行 → 自动补执行 enable（一次，结果如实回填）。
     */
    fun refreshCapabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val grant = SecureAutomation.hasWriteSecureSettings(app)
            var running = SecureAutomation.isA11yRunning(app)
            // BUG-030 对账：持久 ON + 授权在 + 服务未运行 → 补启用（对照 autoWifiAdb 每次 connect 活读的自然对账）
            if (grant && prefs.autoA11y && !running) {
                if (SecureAutomation.enableA11yService(app) is SecureAutomation.CapProbe.Ok) {
                    running = SecureAutomation.waitA11yRunning(app)
                }
            }
            _state.value = _state.value.copy(
                hasSecureGrant = grant,
                a11yRunning = running,
                privateDnsMode = PrivateDns.currentMode(app),
            )
        }
    }

    fun connect() {
        if (_state.value.connecting) return
        _state.value = _state.value.copy(connecting = true, adbStatus = "连接中…")
        connectJob = viewModelScope.launch {
            // BUG-034：连接过程文案节流（首条立即 + 后续 200ms 合并）——note 流不再逐条全树重组
            var lastNoteTime = 0L
            val ok = SecureAutomation.ensureWifiAdbThenConnect(getApplication()) { note ->
                val now = System.currentTimeMillis()
                if (now - lastNoteTime >= 200) {
                    lastNoteTime = now
                    _state.value = _state.value.copy(adbStatus = note)
                }
            }
            if (ok) {
                // BUG-027：用户显式连接成功 → 激活会话（autoApply 服务可工作/自愈）
                TuningExecutor.markSessionActive()
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

    /** BUG-026：connecting 态的取消入口 */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        _state.value = _state.value.copy(
            connecting = false,
            adbStatus = "已取消连接（可重新点连接）",
        )
        // R4-O2 收尾兜底：取消后即使阻塞读晚到完成连接，也被关闭且会话不激活
        //（否则出现「连接活但 sessionActive=false」中间态，服务还会自愈重连违背取消意图）
        viewModelScope.launch(Dispatchers.IO) {
            TuningExecutor.markSessionInactive()
            TuningExecutor.disconnect()
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            // BUG-027：用户显式断开 → 会话结束，autoApply 服务 no-op（在 executor 断开前标记）
            TuningExecutor.markSessionInactive()
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
                // BUG-025：停用结果如实写进 adbStatus（与启用分支文案对称）
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

    // ---- P2-1 OVR Metrics ----

    fun setMetricsOverlay(v: Boolean) { prefs.metricsOverlay = v; resendMetrics() }
    fun setMetricsGraph(v: Boolean) { prefs.metricsGraph = v; resendMetrics() }
    fun setMetricsStat(v: Boolean) { prefs.metricsStat = v; resendMetrics() }
    fun resendMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            OvrMetrics.applyState(
                getApplication(), prefs.metricsOverlay, prefs.metricsGraph, prefs.metricsStat)
        }
    }

    // ---- P2-2 Private DNS ----

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

    fun refreshStatus() {
        viewModelScope.launch {
            val out = TuningExecutor.runCommand(TuningCommands.statusCommand())
            if (out == null) {
                // 读取失败且连接已被清理 → 复位连接状态，允许重连
                _state.value = _state.value.copy(
                    connected = false,
                    adbStatus = "连接已断开: ${TuningExecutor.lastError ?: "读取失败"}",
                )
            }
            _state.value = _state.value.copy(currentStatus = out ?: "读取失败")
        }
    }

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
                // 异常也必须复位 loading（StateFlow 任意线程可写），否则选择器永久「加载中」
                _state.value = _state.value.copy(appsLoading = false)
            }
        }
    }

    fun applyProfile(p: AppProfile) {
        viewModelScope.launch {
            val cmds = p.toCommands()
            // BUG-020：手动应用 → 代次递增，服务后续 Leave 不回滚用户意志
            TuningExecutor.bumpManualGeneration()
            val ok = TuningExecutor.runCommands(cmds)
            val sb = StringBuilder()
            sb.append("应用 ${p.label ?: p.packageName} ")
            sb.append(if (ok) "完成\n" else "失败\n")
            cmds.forEach { sb.append("  $it\n") }
            // 失败时呈现实际命令输出/错误
            if (!ok) sb.append("错误: ${TuningExecutor.lastError}\n")
            _state.value = _state.value.copy(lastCommandOutput = sb.toString())
            if (!ok) {
                _state.value = _state.value.copy(
                    connected = false,
                    adbStatus = "连接已断开: ${TuningExecutor.lastError ?: "命令失败"}",
                )
            }
            refreshStatus()
        }
    }

    fun saveProfile(p: AppProfile) {
        val savedRef = p   // BUG-052：捕获保存时传入的引用，供回填 lambda 比较
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.save(p)
                repo.profiles()
            }.let { ps ->
                _state.value = _state.value.copy(
                    profiles = ps,
                    // BUG-052：条件清——只退出"自己这次保存"的编辑态；保存 IO 期间用户
                    // 已点开另一个编辑（editingProfile 已换引用）则保留之
                    editingProfile = _state.value.editingProfile?.takeIf { it != savedRef },
                )
            }
        }
    }

    // ---- BUG-041：编辑模式与保存前置校验 ----

    /** R6-O2：进入编辑模式（NewProfileCard 预填） */
    fun editProfile(p: AppProfile) {
        _state.value = _state.value.copy(editingProfile = p)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editingProfile = null)
    }

    /** 保存前置检查——包名非法返回错误文案；合法返回 null */
    fun precheckSave(pkg: String): String? {
        if (!ProfileRepository.isValidPackageName(pkg)) {
            return "包名格式不正确（示例 com.example.app，或留空作为全局）"
        }
        return null
    }

    /** 同包名是否已存在（内存 LinkedHashMap 查表，无 IO，主线程可接受） */
    fun profileExists(pkg: String): Boolean = repo.find(pkg) != null

    fun deleteProfile(pkg: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.delete(pkg)
                repo.profiles()
            }.let { ps ->
                _state.value = _state.value.copy(
                    profiles = ps,
                    // BUG-053：删除的是当前编辑对象 → 同步退出编辑态，防止悬挂引用
                    // 让 NewProfileCard 停留编辑态、点保存"复活"已删除的 profile
                    editingProfile = _state.value.editingProfile?.takeIf { it?.packageName != pkg },
                )
            }
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            // BUG-020：手动恢复默认 → 代次递增
            TuningExecutor.bumpManualGeneration()
            val ok = TuningExecutor.runCommands(TuningCommands.resetAll())
            _state.value = _state.value.copy(
                lastCommandOutput = if (ok) "已恢复默认" else "失败: ${TuningExecutor.lastError}"
            )
            if (!ok) {
                _state.value = _state.value.copy(
                    connected = false,
                    adbStatus = "连接已断开: ${TuningExecutor.lastError ?: "命令失败"}",
                )
            }
            refreshStatus()
        }
    }

    // ---- P1-2 自更新 ----

    fun checkUpdate() {
        // O7：防重入——上一次检查未完成时忽略
        if (_state.value.updateChecking) return
        _state.value = _state.value.copy(updateChecking = true, updateStatus = "检查中…")
        viewModelScope.launch {
            try {
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
            } finally {
                _state.value = _state.value.copy(updateChecking = false)
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
            _state.value = _state.value.copy(updateStatus = "下载中…")
            _updateProgress.value = 0
            // R4-O1：清理跨版本残留的陈旧 APK
            SelfUpdater.cleanStaleApks(ctx, info.tag)
            val apk = SelfUpdater.updateApk(ctx, info.tag)
            SelfUpdater.download(info.url, info.size, apk) { p ->
                _updateProgress.value = p        // BUG-034：高频独立 flow，不惊动 UiState
            }.onSuccess { f ->
                // 四重校验失败 → 删文件 + 状态提示，绝不进入安装
                SelfUpdater.verifyApk(ctx, f).onSuccess {
                    _state.value = _state.value.copy(updateStatus = "校验通过，等待系统确认…")
                    _updateProgress.value = -1
                    // BUG-024：安装异常（磁盘满/会话失败）→ 状态提示，不再有崩溃路径
                    SelfUpdater.install(ctx, f).onFailure { e ->
                        f.delete()
                        _state.value = _state.value.copy(
                            updateStatus = "安装失败: ${e.message}（可用 PC adb install 手动更新）")
                        _updateProgress.value = -1
                    }
                }.onFailure { e ->
                    f.delete()
                    _state.value = _state.value.copy(
                        updateStatus = "校验失败，已取消: ${e.message}")
                    _updateProgress.value = -1
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(updateStatus = "下载失败: ${e.message}")
                _updateProgress.value = -1
            }
        }
    }
}
