package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun PlaybackRecoveryCard(error: String, onRetry: () -> Unit, onChooseRoute: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("播放未成功", style = MaterialTheme.typography.titleSmall)
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("重试播放") }
                TvOutlinedButton(onClick = onChooseRoute, modifier = Modifier.weight(1f)) { Text("切换线路") }
            }
        }
    }
}
