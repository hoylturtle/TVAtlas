package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.player.storage.*

@Composable internal fun PlaylistPage(library: Library, busy: Boolean, onAdd: () -> Unit,
    onRefresh: (Playlist) -> Unit, onRefreshBuiltIn: () -> Unit, feedback: String? = null) {
    val builtIn = library.playlists.filter(DefaultPlaylists::contains)
    val custom = library.playlists.filterNot(DefaultPlaylists::contains)
    val enabled = builtIn.filter { it.enabled }.map { it.id }.toSet()
    val count = library.channels.count { ch -> ch.streams.any { it.sourcePlaylistId in enabled } }
    val timestamps = builtIn.filter { it.enabled }.map { it.lastUpdatedAt }
    val updatedAt = if (timestamps.isNotEmpty() && timestamps.all { it != null }) timestamps.filterNotNull().minOrNull() else null
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("频道源", style = MaterialTheme.typography.headlineSmall)
            Text("内置频道统一订阅；也可添加自己的频道源。", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (builtIn.isNotEmpty()) item(key = "built-in-bundle") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TVAtlas 频道订阅", style = MaterialTheme.typography.titleMedium)
                    Text("$count 个频道 · 内置订阅", style = MaterialTheme.typography.bodyMedium)
                    Text("包含常规频道、TVB 官方免费频道、ViuTV 与 ViuTVsix。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("上次完整更新：${date(updatedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (builtIn.any { !it.enabled }) Text("部分内置来源已停用，刷新保留原设置。", style = MaterialTheme.typography.bodySmall)
                    if (feedback != null) Text(feedback, style = MaterialTheme.typography.bodySmall)
                    TvOutlinedButton(onClick = onRefreshBuiltIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "处理中…" else "刷新订阅")
                    }
                    Text("香港官方线路使用香港节点；可用性以官方服务为准。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            TvButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("添加其他频道源") }
        }
        items(custom, key = { it.id }) { playlist ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text("最近更新：${date(playlist.lastUpdatedAt)}", style = MaterialTheme.typography.bodySmall)
                    TvOutlinedButton(onClick = { onRefresh(playlist) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("刷新") }
                }
            }
        }
    }
}
