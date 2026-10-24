package com.example.questtuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UpdateCard(state: UiState, viewModel: TunerViewModel) {
    // BUG-034：进度局部订阅——下载期间只有本 Card 的进度条重组，不再全树刷新
    val progress by viewModel.updateProgress.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("更新", style = MaterialTheme.typography.titleMedium)
            // O8：文案直读，去掉别扭的「当前 …」前缀
            Text(if (state.updateStatus.isEmpty()) "手动检查更新（GitHub Releases，HTTPS）" else state.updateStatus,
                style = MaterialTheme.typography.bodySmall)
            if (progress >= 0) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // O7：检查中禁用，防重入
                OutlinedButton(
                    onClick = viewModel::checkUpdate,
                    enabled = !state.updateChecking,
                ) { Text(if (state.updateChecking) "检查中…" else "检查更新") }
                if (state.updateAvailable) {
                    Button(onClick = viewModel::downloadAndInstall) { Text("下载并安装") }
                }
            }
        }
    }
}
