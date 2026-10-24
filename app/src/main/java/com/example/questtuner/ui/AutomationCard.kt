package com.example.questtuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
                "重启后尽力而为清除调参属性（需无线调试可用）",
                state.restoreOnBoot, grant,
            ) { viewModel.setRestoreOnBoot(it) }

            OvrMetricsSection(state, viewModel)
            PrivateDnsSection(state, viewModel)
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = if (enabled) onChange else null)
    }
}
