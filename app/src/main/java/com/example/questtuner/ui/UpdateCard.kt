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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
