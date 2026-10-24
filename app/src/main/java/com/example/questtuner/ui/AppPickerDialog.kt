package com.example.questtuner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.example.questtuner.tuner.InstalledApp

/** 本地应用选择器：搜索 + 图标列表，零网络。onSelect 返回包名与标签。 */
@Composable
fun AppPickerDialog(
    apps: List<InstalledApp>,
    loading: Boolean,
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
                    if (loading) {
                        // O4：加载中——进度提示 + 按钮防重复
                        androidx.compose.material3.CircularProgressIndicator()
                        Text("加载中…", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onRefresh, enabled = false) { Text("加载应用列表") }
                    } else {
                        Text("应用列表为空", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onRefresh) { Text("加载应用列表") }
                    }
                } else {
                    // R4-O8：搜索无结果显示占位，不再空白
                    if (filtered.isEmpty()) {
                        Text("无匹配应用", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(filtered, key = { it.packageName }) { app ->
                                AppPickerRow(app) { onSelect(app) }
                            }
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
            verticalAlignment = Alignment.CenterVertically,
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
