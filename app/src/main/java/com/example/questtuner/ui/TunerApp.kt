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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                    onEdit = { viewModel.editProfile(p) },   // R6-O2：编辑入口
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
                // BUG-026：connecting 态变取消按钮，协程 cancel 解除阻塞（round-1 取消语义已具备）
                Button(
                    onClick = if (state.connecting) viewModel::cancelConnect else viewModel::connect,
                    enabled = !state.connected || state.connecting,
                ) {
                    Text(if (state.connecting) "取消" else "连接 127.0.0.1:5555")
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
fun ProfileRow(p: AppProfile, connected: Boolean, onEdit: () -> Unit, onApply: () -> Unit, onDelete: () -> Unit) {
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
                OutlinedButton(onClick = onEdit) { Text("编辑") }
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
    val edit = state.editingProfile
    // BUG-041：remember(edit) 键控——编辑对象变化（进编辑/换编辑目标/退出）时全部字段重置
    var pkg by androidx.compose.runtime.remember(edit) {
        androidx.compose.runtime.mutableStateOf(edit?.packageName ?: "")
    }
    var hz by androidx.compose.runtime.remember(edit) {
        androidx.compose.runtime.mutableStateOf(edit?.refreshRate ?: 90)
    }
    // 编辑模式 scale 反推：原 1680→1.0x、3072→~1.83x，coerce 兜住超域历史值
    var scale by androidx.compose.runtime.remember(edit) {
        androidx.compose.runtime.mutableStateOf(
            ((edit?.textureWidth ?: 1680) / 1680f).coerceIn(0.5f, 2.0f))
    }
    var cpu by androidx.compose.runtime.remember(edit) { androidx.compose.runtime.mutableStateOf(edit?.cpuLevel ?: 2) }
    var gpu by androidx.compose.runtime.remember(edit) { androidx.compose.runtime.mutableStateOf(edit?.gpuLevel ?: 2) }
    // pickedPkg/pickedLabel：编辑模式锚定为编辑对象（label 沿用原值）
    var pickedPkg by androidx.compose.runtime.remember(edit) { androidx.compose.runtime.mutableStateOf(edit?.packageName) }
    var pickedLabel by androidx.compose.runtime.remember(edit) { androidx.compose.runtime.mutableStateOf(edit?.label) }
    var showPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // BUG-041：包名校验错误（行内红字）与覆盖确认对话框
    var pkgError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var confirmOverwrite by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    /** 保存动作（终检通过后调用；label 终检逻辑沿用 BUG-031 的 pickedPkg 锚点） */
    fun doSave(targetPkg: String) {
        val w = (1680 * scale).toInt().coerceAtMost(3072)
        val h = (1760 * scale).toInt().coerceAtMost(3200)
        // BUG-031/R4-O6：label 只在包名仍等于选择器所选时采用（防御性终检）
        val label = (if (pkg == pickedPkg) pickedLabel else null) ?: pkg.ifBlank { "全局" }
        viewModel.saveProfile(
            AppProfile(
                packageName = targetPkg,
                label = label,
                refreshRate = hz,
                textureWidth = w,
                textureHeight = h,
                cpuLevel = cpu,
                gpuLevel = gpu,
            )
        )
    }

    if (confirmOverwrite) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("覆盖确认") },
            text = { Text("已存在 ${pkg.ifBlank { "全局" }} 的配置，保存将覆盖旧值。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmOverwrite = false
                    doSave(pkg.ifBlank { "global" })
                }) { Text("覆盖保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmOverwrite = false }) { Text("取消") }
            },
        )
    }

    if (showPicker) {
        AppPickerDialog(
            apps = state.apps,
            loading = state.appsLoading,
            onRefresh = viewModel::loadApps,
            onDismiss = { showPicker = false },
            onSelect = { app ->
                pkg = app.packageName
                pickedPkg = app.packageName      // BUG-031：记录锚点
                pickedLabel = app.label
                showPicker = false
            },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // BUG-041：编辑模式标题与取消入口
            Text(if (edit != null) "编辑 Profile：${edit.label ?: edit.packageName}" else "新建 Profile",
                style = MaterialTheme.typography.titleSmall)
            if (edit != null) {
                androidx.compose.material3.TextButton(onClick = viewModel::cancelEdit) { Text("取消编辑") }
            }
            Text("包名", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.OutlinedTextField(
                value = pkg,
                onValueChange = {
                    pkg = it
                    // BUG-031：手改包名 → 与选择器所选不一致 → 陈旧 label 立即失效
                    if (it != pickedPkg) pickedLabel = null
                    // 输入变化即清除上一轮校验错误
                    pkgError = null
                },
                placeholder = { Text("com.example.app（留空=全局）") },
                singleLine = true,
                isError = pkgError != null,   // R6-O1：失焦/终检错误联动 isError
            )
            OutlinedButton(onClick = {
                // O4：appsLoading 防重复（loadApps 内也有守卫，此处避免无谓触发）
                if (state.apps.isEmpty() && !state.appsLoading) viewModel.loadApps()
                showPicker = true
            }) { Text("选择应用") }
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
            Slider(
                value = scale, onValueChange = { scale = it }, valueRange = 0.5f..2.0f,
                modifier = Modifier.semantics { contentDescription = "纹理缩放" },   // BUG-033
            )
            Text("CPU: $cpu")
            Slider(
                value = cpu.toFloat(), onValueChange = { cpu = it.toInt() }, valueRange = 0f..4f, steps = 3,
                modifier = Modifier.semantics { contentDescription = "CPU 性能等级" },   // BUG-033
            )
            Text("GPU: $gpu")
            Slider(
                value = gpu.toFloat(), onValueChange = { gpu = it.toInt() }, valueRange = 0f..4f, steps = 3,
                modifier = Modifier.semantics { contentDescription = "GPU 性能等级" },   // BUG-033
            )
            Button(
                onClick = {
                    val targetPkg = pkg.ifBlank { "global" }
                    // BUG-041/R6-O1：保存前包名终检
                    pkgError = viewModel.precheckSave(targetPkg)
                    if (pkgError != null) return@Button
                    // BUG-041：新建模式 + 同包名已存在 → 覆盖确认（编辑模式 = 明确意图，不确认）
                    if (edit == null && viewModel.profileExists(targetPkg)) {
                        confirmOverwrite = true
                    } else {
                        doSave(targetPkg)
                    }
                }
            ) { Text(if (edit != null) "保存修改" else "保存 Profile") }
            // 行内错误提示（包名校验）
            pkgError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
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
