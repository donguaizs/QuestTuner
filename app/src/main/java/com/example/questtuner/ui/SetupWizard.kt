package com.example.questtuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
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
                    if (!state.hasSecureGrant) {
                        Text("（未检测到授权，勾选后相应功能仍需手动路径，详见主界面状态提示）",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("以下自动化默认关闭，按需开启（随时可在「自动化与增强」中更改）：")
                    // R4-O5：向导开关门槛与 AutomationCard 语义统一（autoApply 门禁 = a11yRunning）
                    WizardSwitch("连接时自动开启无线调试", state.autoWifiAdb, state.hasSecureGrant) { viewModel.setAutoWifiAdb(it) }
                    WizardSwitch("自动启用无障碍服务", state.autoA11y, state.hasSecureGrant) { viewModel.setAutoA11y(it) }
                    WizardSwitch("按前台应用自动应用 Profile", state.autoApply, state.a11yRunning) { viewModel.setAutoApply(it) }
                    WizardSwitch("开机恢复默认参数", state.restoreOnBoot, state.hasSecureGrant) { viewModel.setRestoreOnBoot(it) }
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                TextButton(onClick = {
                    // BUG-029：用户按指引完成授权后进入第二步——此刻重探测（确定性兜底，不依赖生命周期）
                    viewModel.refreshCapabilities()
                    step = 2
                }) { Text("下一步") }
            } else {
                TextButton(onClick = viewModel::dismissWizard) { Text("开始使用") }
            }
        },
    )
}

@Composable
private fun WizardSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // BUG-033：整行可点 + Switch 语义（与 AutomationCard.SwitchRow 统一）
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
