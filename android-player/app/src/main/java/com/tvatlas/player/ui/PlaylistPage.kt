package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import com.tvatlas.core.playlist.M3uParser
import com.tvatlas.player.source.MyTvSuperSource
import com.tvatlas.player.source.ViuTvSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.player.storage.*

@Composable internal fun PlaylistPage(library: Library, busy: Boolean, onAdd: () -> Unit,
    onRefresh: (Playlist) -> Unit, onRefreshBuiltIn: () -> Unit, feedback: String? = null,
    onEdit: (Playlist) -> Unit = {}, onEnable: (Playlist) -> Unit = {}, onDelete: (Playlist) -> Unit = {}) {
    var details by remember { mutableStateOf(false) }
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
            Card(onClick = { details = true }, modifier = Modifier.fillMaxWidth().tvFocusOutline(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TVAtlas 默认频道", style = MaterialTheme.typography.titleLarge)
                    Text("$count 个频道 · 内置 · 不可删除", style = MaterialTheme.typography.bodyMedium)
                    Text("中国大陆 · 香港 · 澳门 · 台湾 · 日本 · 韩国\nTVB / ViuTV / HOY / 港台官方线路", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TvTextButton(onClick = { details = true }, modifier = Modifier.fillMaxWidth()) { Text("查看内置线路详情") }
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
            Text("我的频道源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            TvButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("添加频道源 / 手机扫码") }
        }
        items(custom, key = { it.id }) { playlist ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (playlist.enabled) "已启用" else "已停用", style = MaterialTheme.typography.bodySmall)
                    Text("最近更新：${date(playlist.lastUpdatedAt)}", style = MaterialTheme.typography.bodySmall)
                    TvOutlinedButton(onClick = { onRefresh(playlist) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("刷新") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvTextButton(onClick = { onEdit(playlist) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("编辑") }
                        TvTextButton(onClick = { onEnable(playlist) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (playlist.enabled) "停用" else "启用") }
                        TvTextButton(onClick = { onDelete(playlist) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("删除") }
                    }
                }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("内置订阅详情") },
        text = {
            var expanded by remember { mutableStateOf<String?>(null) }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$count 个频道 · 中国大陆、香港、澳门、台湾、日本、韩国", style = MaterialTheme.typography.bodyMedium)
                Text("默认频道由 ${builtIn.size} 个内置来源合并展示；刷新失败的来源会保留上次成功内容。", style = MaterialTheme.typography.bodySmall)
                builtIn.forEach { source ->
                    val official = when {
                        source.url == MyTvSuperSource.FREE_PAGE || MyTvSuperSource.recognizes(source.url) -> MyTvSuperSource.playlist(source.url)
                        ViuTvSource.recognizes(source.url) -> ViuTvSource.playlist(source.url)
                        else -> null
                    }
                    val urls = official?.let { M3uParser.parse(it, source.id).flatMap { it.streams }.map { it.url }.toSet() }.orEmpty()
                    val channels = library.channels.filter { ch -> ch.streams.any { it.sourcePlaylistId == source.id || it.url in urls } }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(source.name, style = MaterialTheme.typography.titleSmall)
                            Text("${channels.size} 个频道 · ${if (source.enabled) "启用" else "已停用"}", style = MaterialTheme.typography.bodySmall)
                            Text("最近更新：${date(source.lastUpdatedAt)}", style = MaterialTheme.typography.bodySmall)
                            Text(source.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            TvTextButton(onClick = { expanded = if (expanded == source.id) null else source.id }) {
                                Text(if (expanded == source.id) "收起频道" else "查看频道")
                            }
                            if (expanded == source.id) Text(channels.joinToString("、") { it.name }.ifBlank { "暂无已加载频道" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TvTextButton(onClick = { details = false }) { Text("关闭") } })

}
