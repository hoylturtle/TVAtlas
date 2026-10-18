package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvatlas.player.proxy.CoreInfo

@Composable internal fun CoreManagement(info: CoreInfo, onAppUpdate: () -> Unit) {
    AtlasCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mihomo 内核", style = MaterialTheme.typography.titleMedium)
            Text("当前版本：${info.version}")
            Text("架构：${info.abi}", style = MaterialTheme.typography.bodyMedium)
            Text(if (!info.available) "内核不可用" else if (info.running) "运行中" else "就绪，使用节点时启动", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text("内核随 APP 发版更新。更新 APP 即可同时更新内核，保留订阅和路由配置。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AtlasActionButton(onClick = onAppUpdate, modifier = Modifier.fillMaxWidth()) { Text("检查 APP 更新") }
        }
    }
}
