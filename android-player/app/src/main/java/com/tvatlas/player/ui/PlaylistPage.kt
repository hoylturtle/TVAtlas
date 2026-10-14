package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.player.storage.*

@Composable internal fun PlaylistPage(library: Library, busy: Boolean, onAdd: () -> Unit,
    onRefresh: (Playlist) -> Unit, onRefreshBuiltIn: () -> Unit, feedback: String? = null,
    onEdit: (Playlist) -> Unit = {}, onEnable: (Playlist) -> Unit = {}, onDelete: (Playlist) -> Unit = {}) {
    var details by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<String?>(null) }
    val tv = atlasTv()
    val builtIn = library.playlists.filter(DefaultPlaylists::contains)
    val custom = library.playlists.filterNot(DefaultPlaylists::contains)
    val selectedSourceId = inspected ?: custom.firstOrNull()?.id.takeIf { builtIn.isEmpty() }
    val enabled = builtIn.filter { it.enabled }.map { it.id }.toSet()
    val count = library.channels.count { ch -> ch.streams.any { it.sourcePlaylistId in enabled } }
    val timestamps = builtIn.filter { it.enabled }.map { it.lastUpdatedAt }
    val updatedAt = if (timestamps.isNotEmpty() && timestamps.all { it != null }) timestamps.filterNotNull().minOrNull() else null
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        AtlasHeader("频道源", "默认频道与自己的订阅，统一管理") {
            if (tv) TvOutlinedButton(onClick = onAdd, enabled = !busy) { Text("＋ 添加频道源") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (builtIn.isNotEmpty()) item(key = "built-in-bundle") {
                    Text("内置频道源", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Card(onClick = { inspected = null; if (!tv) details = true },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) inspected = null }.tvFocusOutline(),
                        colors = CardDefaults.cardColors(containerColor = if (tv && inspected == null) MaterialTheme.colorScheme.primary.copy(alpha = .24f) else MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SourceIcon()
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("TVAtlas 默认频道", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("$count 个频道 · 内置 · 不可删除", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text("大陆 / 香港 / 澳门 / 台湾 / 日本 / 韩国", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("更新：${date(updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TvTextButton(onClick = { details = true }) { Text("查看内置线路详情", style = MaterialTheme.typography.labelMedium) }
                                TvTextButton(onClick = onRefreshBuiltIn, enabled = !busy) { Text(if (busy) "处理中…" else "刷新订阅", style = MaterialTheme.typography.labelMedium) }
                            }
                            if (feedback != null) Text(feedback, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("我的频道源", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall) }
                if (custom.isEmpty()) item {
                    Text("暂无自定义频道源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(custom, key = { it.id }) { playlist ->
                    var menu by remember { mutableStateOf(false) }
                    Card(onClick = { inspected = playlist.id }, modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { if (it.hasFocus) inspected = playlist.id }.tvFocusOutline(),
                        colors = CardDefaults.cardColors(containerColor = if (tv && selectedSourceId == playlist.id) MaterialTheme.colorScheme.primary.copy(alpha = .24f) else MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SourceIcon()
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (playlist.enabled) "已启用" else "已停用", style = MaterialTheme.typography.bodySmall)
                                }
                                Box {
                                    TvTextButton(onClick = { menu = true }) { Text("更多") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit(playlist) })
                                        DropdownMenuItem(text = { Text(if (playlist.enabled) "停用" else "启用") }, onClick = { menu = false; onEnable(playlist) })
                                        DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete(playlist) })
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("更新：${date(playlist.lastUpdatedAt)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TvTextButton(onClick = { onRefresh(playlist) }, enabled = !busy) { Text("刷新") }
                            }
                        }
                    }
                }
                item { TvOutlinedButton(onClick = onAdd, enabled = !busy) { Text("添加频道源 / 手机扫码") } }
            }
            if (tv) {
                val selected = custom.firstOrNull { it.id == selectedSourceId }
                val channels = library.channels.filter { ch -> ch.streams.any { if (selected == null) it.sourcePlaylistId in enabled else it.sourcePlaylistId == selected.id } }
                AtlasPanel(Modifier.weight(1.1f).fillMaxHeight()) {
                    Text(selected?.name ?: if (builtIn.isNotEmpty()) "TVAtlas 默认频道" else "来源详情", style = MaterialTheme.typography.titleLarge)
                    Text("${channels.size} 个频道 · 订阅详情", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (selected == null) Text("TVB / ViuTV / HOY / 港台 · 大陆及港澳台日韩频道", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(channels.hongKongFirst(), key = { it.id }) { channel ->
                            Row(Modifier.fillMaxWidth().tvFocusOutline().focusable().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ChannelArtwork(channel)
                                Column { Text(channel.name, style = MaterialTheme.typography.bodyMedium); Text("${channel.browseRegion()} / ${channel.browseType()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        if (channels.isEmpty()) item { Text("尚无已加载频道", style = MaterialTheme.typography.bodyMedium) }
                    }
                    Text("方向键选择来源 · 确认查看详情 · 返回直播", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable private fun SourceIcon() {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = .18f)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}
