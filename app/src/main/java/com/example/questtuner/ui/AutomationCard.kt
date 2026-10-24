package com.example.questtuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.questtuner.tuner.OvrMetrics
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
                // R4-O4：副文案按实态区分，消除静默失效
                if (state.a11yRunning) "仅本地监听窗口切换，不上传任何数据；需已连接——断开后自动应用暂停，重新连接后恢复"
                else "无障碍服务未运行，自动应用不会触发——请先开启上方「自动启用无障碍服务」",
                state.autoApply, state.a11yRunning,   // BUG-030：门禁 = 真实依赖（服务运行）
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
        Modifier.fillMaxWidth()
            // BUG-033：整行可点 + Switch 语义——TalkBack 聚焦读出「标题、副文案、开关、状态」，触达面积更大
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
        // onCheckedChange=null：点击语义由整行承载，避免 TalkBack 双重播报
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun OvrMetricsSection(state: UiState, viewModel: TunerViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // O3：包安装检测结果缓存——避免每次重组都发一次 PackageManager IPC
    val available = remember(ctx) { OvrMetrics.isAvailable(ctx) }
    Text("OVR Metrics 性能叠加层", style = MaterialTheme.typography.bodyMedium)
    if (!available) {
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
        verticalAlignment = Alignment.CenterVertically) {
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
