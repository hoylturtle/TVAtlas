@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch

/** TV navigation occupies its own column; programme cards never push navigation off screen. */
@Composable internal fun TvChannelBrowser(library: Library, status: PlaybackStatus, modifier: Modifier,
    onPlay: (Channel) -> Unit, onDetails: (Channel) -> Unit, onAdd: () -> Unit,
    favorites: Set<String>, recent: List<String>, onFavorite: (Channel) -> Unit,
    programme: @Composable (Channel) -> Unit, allowArtworkNetwork: () -> Boolean, searchRequest: Int, guide: @Composable (Channel) -> Unit = programme) {
    val overlay = LocalLiveOverlay.current
    var filtersOpen by remember { mutableStateOf(false) }
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var lastFocused by rememberSaveable { mutableStateOf(status.channelId) }
    val grid = rememberLazyListState()
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    var guideChannel by rememberSaveable { mutableStateOf<String?>(null) }
    val guideFocus = remember { FocusRequester() }
    val guideVisibility = LocalLiveGuideOpen.current

    val scope = rememberCoroutineScope()
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    val firstFocus = remember { FocusRequester() }
    var restoreRequested by remember { mutableStateOf(false) }
    fun closeGuide() { guideOpen = false; restoreRequested = true }
    fun showGuide(channel: Channel) { lastFocused = channel.id; if (guideOpen) guideChannel = channel.id; guideOpen = true }
    BackHandler(search && !guideOpen) { search = false; query = ""; restoreRequested = true }
    BackHandler(guideOpen) { closeGuide() }
    val input = LocalInputModeManager.current
    val index = rememberChannelBrowseIndex(library.channels)
    if (index == null) { LinearProgressIndicator(modifier.fillMaxWidth()); return }
    LaunchedEffect(guideOpen, index) { guideVisibility?.invoke(guideOpen); if (guideOpen) { withFrameNanos { }; runCatching { guideFocus.requestFocus() } } }
    val sorted = index.channels
    val regions = index.regions
    val selectedRegion = region?.takeIf { it in regions } ?: sorted.firstOrNull { it.id == status.channelId }?.let(index::region) ?: regions.firstOrNull()
    val types = remember(index, selectedRegion) { sorted.filter { index.region(it) == selectedRegion }.map { index.type(it) }.distinct() }
    val selectedType = type?.takeIf { it in types || (overlay && it == "全部类型") } ?: if (overlay) "全部类型" else types.firstOrNull()
    val shown = remember(index, selectedRegion, selectedType, query, mode, favorites, recent, overlay) {
        val base = sorted.filter { channel ->
            when (mode) {
                1 -> channel.id in favorites
                2 -> channel.id in recent
                else -> if (query.isBlank()) index.region(channel) == selectedRegion && (selectedType == "全部类型" || index.type(channel) == selectedType) else true
            } && (query.isBlank() || channel.name.contains(query, true) || index.region(channel).contains(query, true) || index.type(channel).contains(query, true))
        }
        if (mode == 2) { val positions = recent.withIndex().associate { it.value to it.index }; base.sortedBy { positions[it.id] ?: Int.MAX_VALUE } }
        else if (overlay && mode == 0 && query.isBlank() && selectedType == "全部类型") base.groupBy { index.type(it) }.values.flatMap { index.primaryFirst(it) }
        else index.primaryFirst(base)
    }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { search = true; mode = 0 } }
    Row(modifier.padding(if (overlay) 0.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(if (overlay) 0.dp else 12.dp)) {
        LazyColumn(Modifier.width(if (overlay) 88.dp else 100.dp).fillMaxHeight().focusGroup().testTag("channel-categories"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!overlay) item {
                listOf("全部", "收藏", "最近").forEachIndexed { index, label ->
                    TvTextButton(onClick = { mode = index; scope.launch { grid.scrollToItem(0) } }, modifier = Modifier.fillMaxWidth().selectionOutline(mode == index)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)) { Text(label) }
                }
            }
            if (mode == 0) regions.forEach { label ->
                item(key = "region:$label") {
                    TvTextButton(onClick = { region = label; type = null; query = ""; mode = 0; scope.launch { grid.scrollToItem(0) } }, modifier = Modifier.fillMaxWidth().height(if (overlay) 60.dp else 48.dp).selectionOutline(label == selectedRegion)
                        .then(if (overlay && label == regions.firstOrNull()) Modifier.focusRequester(firstFocus) else Modifier)) { Text(label) }
                }
                if (!overlay && label == selectedRegion) types.forEach { category ->
                    item(key = "type:$label:$category") {
                        TvTextButton(onClick = { type = category; query = ""; scope.launch { grid.scrollToItem(0) } }, modifier = Modifier.fillMaxWidth().padding(start = 10.dp).selectionOutline(category == selectedType)) {
                            Text(category, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (!overlay) item { TvTextButton(onClick = onAdd) { Text("管理来源") } }
        }
        if (overlay) VerticalDivider(Modifier.fillMaxHeight(), color = Color.White.copy(alpha = .16f))
        Column(Modifier.weight(1f).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (overlay) Box(Modifier.weight(1f)) {
                    TvTextButton(onClick = { filtersOpen = true }) { Text(when (mode) { 1 -> "收藏"; 2 -> "最近"; else -> selectedType ?: "频道" }, style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(expanded = filtersOpen, onDismissRequest = { filtersOpen = false }) {
                        (listOf("全部类型") + types).forEach { category -> DropdownMenuItem(text = { Text(category) }, onClick = { type = category; mode = 0; query = ""; filtersOpen = false; scope.launch { grid.scrollToItem(0) } }) }
                        listOf("收藏", "最近").forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = { mode = index + 1; filtersOpen = false; scope.launch { grid.scrollToItem(0) } }) }
                        DropdownMenuItem(text = { Text("管理来源") }, onClick = { filtersOpen = false; onAdd() })
                    }
                } else Text(if (mode == 0 && query.isBlank() && selectedRegion != null) "$selectedRegion · $selectedType · ${shown.size} 个频道" else "${shown.size} 个频道", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TvTextButton(onClick = { search = !search; if (!search) query = "" }) { Text(if (search) "收起" else "搜索") }
            }
            if (search) OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("搜索频道") }, singleLine = true)
            if (shown.isEmpty()) Text(if (library.channels.isEmpty()) "请先添加频道源" else "暂无匹配频道")
            LazyColumn(state = grid, modifier = Modifier.weight(1f).testTag("channel-list"),
                verticalArrangement = Arrangement.spacedBy(if (overlay) 0.dp else 2.dp), contentPadding = PaddingValues(if (overlay) 0.dp else 4.dp)) {
                itemsIndexed(shown, key = { _, channel -> channel.id }) { index, channel ->
                    val playing = status.channelId == channel.id
                    val channelFocus = remember(channel.id) { FocusRequester() }
                    DisposableEffect(channel.id, channelFocus) {
                        rowFocus[channel.id] = channelFocus
                        onDispose { if (rowFocus[channel.id] === channelFocus) rowFocus.remove(channel.id) }
                    }
                    Surface(color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = if (overlay) RoundedCornerShape(6.dp) else MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().height(if (overlay) 64.dp else 72.dp).testTag("channel:${channel.id}")
                            .focusRequester(channelFocus)
                            .onFocusChanged { if (it.hasFocus) { lastFocused = channel.id; if (guideOpen) guideChannel = channel.id } }.then(if (overlay) Modifier.liveFocusOutline() else Modifier.tvFocusOutline())
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    showGuide(channel); true
                                } else false
                            }
                            .combinedClickable(onClick = { onPlay(channel) }, onLongClick = { onDetails(channel) })) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (overlay) Text("${index + 1}", Modifier.width(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            ChannelArtwork(channel, !overlay && playing && status.successAt != null, allowArtworkNetwork,
                                modifier = if (overlay) Modifier.width(52.dp).height(44.dp) else Modifier.width(64.dp).height(48.dp), compactLogo = overlay)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = if (overlay) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                                    color = if (playing && !overlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium)
                                programme(channel)
                            }
                            if (overlay) TvIconButton(onClick = { showGuide(channel) }) {
                                Icon(if (playing) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.List,
                                    contentDescription = "${channel.name}节目单", tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                            } else TvTextButton(onClick = { showGuide(channel) }) { Text("节目", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    if (overlay) HorizontalDivider(color = Color.White.copy(alpha = .14f))
                }
            }
        }
        if (guideOpen) {
            if (overlay) VerticalDivider(Modifier.fillMaxHeight(), color = Color.White.copy(alpha = .16f))
            Column(Modifier.widthIn(min = 180.dp, max = if (overlay) 260.dp else 240.dp).fillMaxHeight().testTag("channel-guide")
                .focusGroup().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                        closeGuide(); true
                    } else false
                }.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextButton(onClick = { closeGuide() }, modifier = Modifier.focusRequester(guideFocus)) { Text("收起节目单") }
                library.channels.firstOrNull { it.id == guideChannel }?.let { channel ->
                    if (!overlay) Text(channel.name, style = MaterialTheme.typography.titleMedium)
                    guide(channel)
                    TvTextButton(onClick = { onFavorite(channel) }) { Text(if (channel.id in favorites) "取消收藏" else "收藏频道") }
                    TvTextButton(onClick = { onDetails(channel) }) { Text("线路详情") }
                }
            }
        }
    }
    LaunchedEffect(restoreRequested, guideOpen, shown) {
        if (restoreRequested && !guideOpen) {
            val target = shown.indexOfFirst { it.id == lastFocused }
            if (target >= 0) {
                grid.scrollToItem(target)
                withFrameNanos { }
                runCatching { rowFocus[lastFocused]?.requestFocus() }
            } else { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
            restoreRequested = false
        }
    }
    LaunchedEffect(library.channels.isNotEmpty()) {
        if (library.channels.isNotEmpty()) {
            input.requestInputMode(InputMode.Keyboard)
            val target = shown.indexOfFirst { it.id == (lastFocused ?: status.channelId) }
            if (target >= 0) {
                lastFocused = shown[target].id
                grid.scrollToItem(target)
                withFrameNanos { }
                runCatching { rowFocus[lastFocused]?.requestFocus() }
            } else {
                withFrameNanos { }
                runCatching { firstFocus.requestFocus() }
            }
        }
    }
}
