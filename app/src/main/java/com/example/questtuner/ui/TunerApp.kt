package com.example.questtuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.questtuner.tuner.AppProfile

@Composable
fun TunerApp(viewModel: TunerViewModel) {
    val state by viewModel.state.collectAsState()
    MaterialTheme {
        if (state.wizardVisible) {
            SetupWizard(state, viewModel)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionCard(state, viewModel) }
            item { StatusCard(state) }
            item { QuickTuneCard(state, viewModel) }
            item { AutomationCard(state, viewModel) }
            item { UpdateCard(state, viewModel) }
            item { Text("按应用 Profile", style = MaterialTheme.typography.titleMedium) }
            items(state.profiles, key = { it.packageName }) { p ->
                ProfileRow(
                    p,
                    connected = state.connected,
                    onApply = { viewModel.applyProfile(p) },
                    onDelete = { viewModel.deleteProfile(p.packageName) },
                )
            }
            item { Text("新建 Profile", style = MaterialTheme.typography.titleMedium) }
            item { NewProfileCard(state, viewModel) }
            item { LogCard(state) }
        }
    }
}

@Composable
fun ConnectionCard(state: UiState, viewModel: TunerViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ADB 连接", style = MaterialTheme.typography.titleMedium)
            Text(state.adbStatus, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::connect, enabled = !state.connected && !state.connecting) {
                    Text(if (state.connecting) "连接中…" else "连接 127.0.0.1:5555")
                }
                OutlinedButton(onClick = viewModel::refreshStatus, enabled = state.connected) {
                    Text("刷新状态")
                }
                OutlinedButton(onClick = viewModel::disconnect, enabled = state.connected) {
                    Text("断开")
                }
            }
        }
    }
}

@Composable
fun StatusCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("当前参数", style = MaterialTheme.typography.titleMedium)
            Text(
                state.currentStatus.ifEmpty { "尚未读取" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun QuickTuneCard(state: UiState, viewModel: TunerViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("快速调节", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(72, 90, 120).forEach { hz ->
                    OutlinedButton(
                        onClick = {
                            viewModel.applyProfile(
                                AppProfile(packageName = "global", label = "全局 ${hz}Hz", refreshRate = hz)
                            )
                        },
                        enabled = state.connected,
                    ) { Text("${hz}Hz") }
                }
            }
            OutlinedButton(onClick = viewModel::resetAll, enabled = state.connected) {
                Text("恢复默认")
            }
        }
    }
}

@Composable
fun ProfileRow(p: AppProfile, connected: Boolean, onApply: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.label ?: p.packageName, style = MaterialTheme.typography.bodyLarge)
                Text(describeProfile(p), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onApply, enabled = connected) { Text("应用") }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun describeProfile(p: AppProfile): String = buildString {
    p.refreshRate?.let { append("${it}Hz ") }
    if (p.textureWidth != null && p.textureHeight != null) {
        append("${p.textureWidth}x${p.textureHeight} ")
    }
    p.cpuLevel?.let { append("CPU$it ") }
    p.gpuLevel?.let { append("GPU$it") }
    ifEmpty { "（未设参数）" }
}

@Composable
fun NewProfileCard(state: UiState, viewModel: TunerViewModel) {
    var pkg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var hz by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(90) }
    var scale by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1.0f) }
    var cpu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(2) }
    var gpu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(2) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("包名", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.OutlinedTextField(
                value = pkg,
                onValueChange = { pkg = it },
                placeholder = { Text("com.example.app（留空=全局）") },
                singleLine = true,
            )
            Text("刷新率: ${hz}Hz")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(72, 90, 120).forEach { c ->
                    androidx.compose.material3.FilterChip(
                        selected = hz == c,
                        onClick = { hz = c },
                        label = { Text("$c") },
                    )
                }
            }
            Text("纹理缩放: ${"%.2f".format(scale)}x")
            Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.5f..2.0f)
            Text("CPU: $cpu")
            Slider(value = cpu.toFloat(), onValueChange = { cpu = it.toInt() }, valueRange = 0f..4f, steps = 3)
            Text("GPU: $gpu")
            Slider(value = gpu.toFloat(), onValueChange = { gpu = it.toInt() }, valueRange = 0f..4f, steps = 3)
            Button(
                onClick = {
                    val w = (1680 * scale).toInt().coerceAtMost(3072)
                    val h = (1760 * scale).toInt().coerceAtMost(3200)
                    viewModel.saveProfile(
                        AppProfile(
                            packageName = pkg.ifBlank { "global" },
                            label = pkg.ifBlank { "全局" },
                            refreshRate = hz,
                            textureWidth = w,
                            textureHeight = h,
                            cpuLevel = cpu,
                            gpuLevel = gpu,
                        )
                    )
                }
            ) { Text("保存 Profile") }
        }
    }
}

@Composable
fun LogCard(state: UiState) {
    if (state.lastCommandOutput.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("命令输出", style = MaterialTheme.typography.titleMedium)
            Text(state.lastCommandOutput, style = MaterialTheme.typography.bodySmall)
        }
    }
}
