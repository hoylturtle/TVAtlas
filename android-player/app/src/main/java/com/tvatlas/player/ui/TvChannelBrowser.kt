@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import androidx.compose.ui.input.key.*
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Library

/** TV navigation occupies its own column; programme cards never push navigation off screen. */
@Composable internal fun TvChannelBrowser(library: Library, status: PlaybackStatus, modifier: Modifier,
    onPlay: (Channel) -> Unit, onDetails: (Channel) -> Unit, onAdd: () -> Unit,
    favorites: Set<String>, recent: List<String>, onFavorite: (Channel) -> Unit,
    programme: @Composable (Channel) -> Unit, allowArtworkNetwork: () -> Boolean, searchRequest: Int) {
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val restoreFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    val sorted = remember(library.channels) { library.channels.hongKongFirst() }
    val regions = remember(sorted) { sorted.map { it.browseRegion() }.distinct() }
    val selectedRegion = region?.takeIf { it in regions } ?: sorted.firstOrNull { it.id == status.channelId }?.browseRegion() ?: regions.firstOrNull()
    val types = remember(sorted, selectedRegion) { sorted.filter { it.browseRegion() == selectedRegion }.map { it.browseType() }.distinct() }
    val selectedType = type?.takeIf { it in types } ?: types.firstOrNull()
    val shown = remember(sorted, selectedRegion, selectedType, query, mode, favorites, recent) {
        val base = sorted.filter { channel ->
            when (mode) {
                1 -> channel.id in favorites
                2 -> channel.id in recent
                else -> if (query.isBlank()) channel.browseRegion() == selectedRegion && channel.browseType() == selectedType else true
            } && (query.isBlank() || channel.name.contains(query, true) || channel.browseRegion().contains(query, true) || channel.browseType().contains(query, true))
        }
        if (mode == 2) base.sortedBy { recent.indexOf(it.id) } else base.primaryChannelsFirst()
    }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { search = true; mode = 0 } }
    Row(modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.width(128.dp).fillMaxHeight().focusGroup().testTag("channel-categories"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                listOf("全部", "收藏", "最近").forEachIndexed { index, label ->
                    TvTextButton(onClick = { mode = index }, modifier = Modifier.fillMaxWidth().selectionOutline(mode == index)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)) { Text(label) }
                }
            }
            if (mode == 0) regions.forEach { label ->
                item(key = "region:$label") {
                    TvTextButton(onClick = { region = label; type = null; query = "" }, modifier = Modifier.fillMaxWidth().selectionOutline(label == selectedRegion)) { Text(label) }
                }
                if (label == selectedRegion) types.forEach { category ->
                    item(key = "type:$label:$category") {
                        TvTextButton(onClick = { type = category }, modifier = Modifier.fillMaxWidth().padding(start = 10.dp).selectionOutline(category == selectedType)) {
                            Text(category, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item { TvTextButton(onClick = onAdd) { Text("管理来源") } }
        }
        Column(Modifier.weight(1f).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (mode == 0 && query.isBlank() && selectedRegion != null) "$selectedRegion · $selectedType · ${shown.size} 个频道" else "${shown.size} 个频道", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TvTextButton(onClick = { search = !search; if (!search) query = "" }) { Text(if (search) "收起" else "搜索") }
            }
            if (search) OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("搜索频道") }, singleLine = true)
            if (shown.isEmpty()) Text(if (library.channels.isEmpty()) "请先添加频道源" else "暂无匹配频道")
            LazyVerticalGrid(columns = GridCells.Adaptive(196.dp), state = grid, modifier = Modifier.weight(1f).testTag("channel-list"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                gridItems(shown, key = { it.id }) { channel ->
                    val playing = status.channelId == channel.id
                    Surface(color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().height(168.dp).testTag("channel:${channel.id}")
                            .then(if (lastFocused == channel.id) Modifier.focusRequester(restoreFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) lastFocused = channel.id }.tvFocusOutline()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.isLongPress &&
                                    event.nativeKeyEvent.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER)) {
                                    onDetails(channel); true
                                } else false
                            }
                            .combinedClickable(onClick = { onPlay(channel) }, onLongClick = { onDetails(channel) })) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                ChannelArtwork(channel, playing && status.successAt != null, allowArtworkNetwork)
                                Text(channel.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            }
                            Column(Modifier.weight(1f)) { programme(channel) }
                            TvTextButton(onClick = { onFavorite(channel) }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                                Text(if (channel.id in favorites) "★ 已收藏" else "☆ 收藏", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(library.channels.isNotEmpty()) {
        if (library.channels.isNotEmpty()) {
            input.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            val visible = lastFocused != null && grid.layoutInfo.visibleItemsInfo.any { it.key == lastFocused }
            val restored = visible && runCatching { restoreFocus.requestFocus() }.getOrDefault(false)
            if (!restored) runCatching { firstFocus.requestFocus() }
        }
    }
}
