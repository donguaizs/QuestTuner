package com.example.questtuner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.questtuner.tuner.AppProfile
import com.example.questtuner.tuner.AppScanner
import com.example.questtuner.tuner.InstalledApp
import com.example.questtuner.tuner.ProfileRepository
import com.example.questtuner.tuner.TuningCommands
import com.example.questtuner.tuner.TuningExecutor
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
    val profiles: List<AppProfile> = emptyList(),
    val lastCommandOutput: String = "",
)

class TunerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository.of(app.filesDir)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        _state.value = _state.value.copy(profiles = repo.profiles())
    }

    fun connect() {
        if (_state.value.connecting) return
        _state.value = _state.value.copy(connecting = true, adbStatus = "连接中…")
        viewModelScope.launch {
            val ok = TuningExecutor.ensureConnected(getApplication())
            if (ok) {
                refreshStatus()
                _state.value = _state.value.copy(
                    connected = true,
                    connecting = false,
                    adbStatus = "已连接 adbd (127.0.0.1:5555)",
                )
                loadApps()
            } else {
                _state.value = _state.value.copy(
                    connected = false,
                    connecting = false,
                    adbStatus = "连接失败: ${TuningExecutor.lastError}",
                )
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val out = TuningExecutor.runCommand(TuningCommands.statusCommand())
            _state.value = _state.value.copy(currentStatus = out ?: "读取失败")
        }
    }

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = AppScanner.query(getApplication())
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(apps = apps)
            }
        }
    }

    fun applyProfile(p: AppProfile) {
        viewModelScope.launch {
            val cmds = p.toCommands()
            val ok = TuningExecutor.runCommands(cmds)
            val sb = StringBuilder()
            sb.append("应用 ${p.label ?: p.packageName} ")
            sb.append(if (ok) "完成\n" else "失败\n")
            cmds.forEach { sb.append("  $it\n") }
            if (!ok) sb.append("错误: ${TuningExecutor.lastError}\n")
            _state.value = _state.value.copy(lastCommandOutput = sb.toString())
            refreshStatus()
        }
    }

    fun saveProfile(p: AppProfile) {
        viewModelScope.launch {
            repo.save(p)
            _state.value = _state.value.copy(profiles = repo.profiles())
        }
    }

    fun deleteProfile(pkg: String) {
        viewModelScope.launch {
            repo.delete(pkg)
            _state.value = _state.value.copy(profiles = repo.profiles())
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            val ok = TuningExecutor.runCommands(TuningCommands.resetAll())
            _state.value = _state.value.copy(
                lastCommandOutput = if (ok) "已恢复默认" else "失败: ${TuningExecutor.lastError}"
            )
            refreshStatus()
        }
    }
}
