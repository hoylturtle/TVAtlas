@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tvatlas.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import com.tvatlas.core.vod.*
import kotlinx.coroutines.*

/** Film-first browsing: providers are kept in source management, never used as user-facing shelves. */
@Composable internal fun UnifiedVodPage(model: com.tvatlas.player.PlayerViewModel, onFiles: () -> Unit) {
    val repository = model.unifiedVod
    val state by repository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.syncVodSubscriptions() }
    val selecting by model.vodSelecting.collectAsStateWithLifecycle()
    var kindName by rememberSaveable { mutableStateOf(VodKind.MOVIE.name) }
    var genre by rememberSaveable { mutableStateOf("全部") }
    var region by rememberSaveable { mutableStateOf("全部") }
    var year by rememberSaveable { mutableStateOf("全部") }
    var sortName by rememberSaveable { mutableStateOf(VodSort.DEFAULT.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    val filter = VodFilter(VodKind.valueOf(kindName).let { if (it == VodKind.OTHER) VodKind.SHORT_DRAMA else it }, genre, region, year, VodSort.valueOf(sortName)).let { it.copy(genre = UnifiedVod.genreLabel(it.kind, it.genre)) }
    var filtersOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchDraft by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<UnifiedMovie?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var returnFocusId by remember { mutableStateOf<String?>(null) }
    var loader by remember { mutableStateOf<ImageLoader?>(null) }
    val scroll = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var detailJob by remember { mutableStateOf<Job?>(null) }
    var detailGeneration by remember { mutableLongStateOf(0) }
    fun browse(more: Boolean = false, force: Boolean = false) = model.cloudNetworkAction {
        repository.browse(filter, search, more, force)
        if (loader == null) scope.launch { try { loader = model.createVodArtworkLoader() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {} }
    }
    fun submitSearch() {
        val next = query.trim()
        if (search == next) browse(force = true) else search = next
    }
    fun showDetails(item: UnifiedMovie) {
        val token = ++detailGeneration
        detailJob?.cancel(); model.cancelVodSelection()
        selected = item; detailLoading = true; detailError = null
        model.cloudNetworkAction {
            if (token != detailGeneration) return@cloudNetworkAction
            detailJob = scope.launch {
                try { val fresh = model.unifiedDetails(item); if (token == detailGeneration) selected = fresh }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (token == detailGeneration) detailError = error.message ?: "读取影片失败" }
                finally { if (token == detailGeneration) detailLoading = false }
            }
        }
    }
    fun backToLibrary() {
        returnFocusId = selected?.id
        detailGeneration++; detailJob?.cancel(); model.cancelVodSelection()
        selected = null; detailLoading = false; detailError = null
    }
    VodBrowseOnEntry(filter, search, scroll) {
        backToLibrary(); returnFocusId = null; browse(force = true)
    }
    DisposableEffect(repository) {
        model.setVodPageActive(true)
        onDispose { model.setVodPageActive(false); detailJob?.cancel(); model.cancelVodSelection() }
    }
    DisposableEffect(loader) { val owned = loader; onDispose { owned?.shutdown() } }
    BackHandler(selected != null) { backToLibrary() }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val wide = atlasTv() || maxWidth >= 720.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide) Column(Modifier.width(112.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("影视", style = MaterialTheme.typography.titleLarge)
                UnifiedVod.browseKinds.forEach { kind ->
                    TvTextButton(onClick = { kindName = kind.name; genre = "全部"; region = "全部"; year = "全部" },
                        modifier = Modifier.fillMaxWidth().selectionOutline(filter.kind == kind)) { Text(kind.label) }
                }
                Spacer(Modifier.weight(1f))
                TvOutlinedButton(onClick = onFiles, modifier = Modifier.fillMaxWidth()) { Text("网盘文件") }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null) TvIconButton(onClick = ::backToLibrary) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回片库") }
                    Column(Modifier.weight(1f)) {
                        Text(if (selected == null) "影视片库" else "影片详情", style = MaterialTheme.typography.titleLarge)
                        Text(if (selected == null) "最近更新 · 分类浏览 · 高清优先" else listOf(selected!!.movie.year, selected!!.movie.area).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected == null) {
                        TvTextButton(onClick = { searchDraft = query; searchOpen = true }, modifier = Modifier.testTag("vod-search-open")) { Text("搜索") }
                        TvTextButton(onClick = { filtersOpen = true }) { Text("筛选") }
                    }
                    if (!wide) TvTextButton(onClick = onFiles) { Text("网盘") }
                }
                if (!wide && selected == null) AtlasTabs(UnifiedVod.browseKinds.map { it.label }, UnifiedVod.browseKinds.indexOf(filter.kind)) { index ->
                    kindName = UnifiedVod.browseKinds[index].name; genre = "全部"; region = "全部"; year = "全部"
                }
                val current = selected
                if (current == null) {
                    if (wide) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("类型：${filter.genre}", "地区：${filter.region}", "年份：${filter.year}", "排序：${filter.sort.label}").forEach { label ->
                            TvOutlinedButton(onClick = { filtersOpen = true }) { Text(label) }
                        }
                    } else Text(listOf(filter.genre, filter.region, filter.year).filter { it != "全部" }.joinToString(" · ").ifBlank { "全部类型 · 全部地区 · 全部年份" },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (search.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索：$search", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        TvTextButton(onClick = { query = ""; search = "" }) { Text("清除搜索") }
                    }
                    if (state.loading) {
                        LinearProgressIndicator(progress = { if (state.total > 0) state.completed.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        Text(if (state.showingCache && state.items.isNotEmpty()) "已显示缓存片单，后台更新中 · ${state.completed}/${state.total} 个来源"
                            else if (state.items.isNotEmpty()) "已显示 ${state.items.size} 部，继续加载 · ${state.completed}/${state.total} 个来源"
                            else "正在加载${filter.kind.label}片单 · ${state.completed}/${state.total} 个来源", style = MaterialTheme.typography.bodyMedium)
                        if (state.items.isEmpty()) Text("首次打开此分类可能较慢，已返回的影片会陆续显示。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!state.loading) state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (state.items.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loading) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(Modifier.size(40.dp))
                            Text("正在加载${filter.kind.label}片单…", style = MaterialTheme.typography.titleLarge)
                            Text("已完成 ${state.completed}/${state.total} 个来源，影片会陆续显示", style = MaterialTheme.typography.bodyMedium)
                        } else Text("暂未加载到符合筛选的影片\n可调整条件、搜索片名或继续加载。", style = MaterialTheme.typography.bodyMedium)
                    } else VodMovieGrid(state.items.map { it.movie }, loader, model.canLoadChannelArtwork(), Modifier.weight(1f), scroll, returnFocusId) { movie ->
                        state.items.firstOrNull { it.id == movie.id }?.let(::showDetails)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text((if (state.showingCache) "上次浏览 · " else "") + "已显示 ${state.items.size} 部", style = MaterialTheme.typography.labelMedium)
                        TvTextButton(onClick = { browse(force = true) }, enabled = !state.loading) { Text("刷新") }
                        TvOutlinedButton(onClick = { browse(true) }, enabled = !state.loading && state.canLoadMore) { Text("加载更多") }
                    }
                } else {
                    if (detailLoading || selecting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (selecting) "正在选择高清可用版本…" else "正在查找可播放版本…", style = MaterialTheme.typography.labelSmall)
                    }
                    detailError?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TvOutlinedButton(onClick = { showDetails(current) }) { Text("重试") }
                    }
                    VodMovieDetails(current.movie, loader, model.canLoadChannelArtwork(), detailLoading || selecting, Modifier.weight(1f)) { episode ->
                        model.openUnifiedChoice(episode, current.id + ":" + VodEpisodeChoices.identity(episode.name), current.movie.name + " · " + episode.name)
                    }
                }
            }
        }
    }
    if (searchOpen) AlertDialog(onDismissRequest = { searchOpen = false }, title = { Text("搜索${filter.kind.label}") }, text = {
        OutlinedTextField(searchDraft, { searchDraft = it.take(100) }, Modifier.fillMaxWidth().testTag("vod-search-input"), singleLine = true,
            placeholder = { Text("输入片名") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { query = searchDraft; searchOpen = false; submitSearch() }))
    }, confirmButton = { TvButton(onClick = { query = searchDraft; searchOpen = false; submitSearch() }) { Text("搜索") } },
        dismissButton = {
            TvTextButton(onClick = { query = ""; search = ""; searchOpen = false }) { Text("清除搜索") }
            TvTextButton(onClick = { searchOpen = false }) { Text("取消") }
        })
    if (filtersOpen) VodFilterDialog(filter, { filtersOpen = false }) { chosen ->
        kindName = chosen.kind.name; genre = chosen.genre; region = chosen.region; year = chosen.year; sortName = chosen.sort.name; filtersOpen = false
    }
}

@Composable internal fun VodFilterDialog(current: VodFilter, onDismiss: () -> Unit, onApply: (VodFilter) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    val thisYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("筛选影片") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterOptions("内容", UnifiedVod.browseKinds.map { it.label }, draft.kind.label) { label -> draft = VodFilter(kind = UnifiedVod.browseKinds.first { it.label == label }) }
            FilterOptions("类型", UnifiedVod.genresFor(draft.kind), draft.genre) { draft = draft.copy(genre = it) }
            FilterOptions("地区", UnifiedVod.seriesGroups, draft.region) { draft = draft.copy(region = it) }
            FilterOptions("年份", listOf("全部") + (thisYear downTo 2000).map { it.toString() } + listOf("更早", "未知"), draft.year) { draft = draft.copy(year = it) }
            FilterOptions("排序", VodSort.entries.map { it.label }, draft.sort.label) { label -> draft = draft.copy(sort = VodSort.entries.first { it.label == label }) }
        }
    }, confirmButton = { TvButton(onClick = { onApply(draft) }) { Text("应用筛选") } }, dismissButton = {
        TvTextButton(onClick = { draft = VodFilter(kind = current.kind) }) { Text("重置") }
        TvTextButton(onClick = onDismiss) { Text("取消") }
    })
}
@Composable private fun FilterOptions(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.distinct().forEach { option -> AtlasChip(option, option == selected, { onSelect(option) }) }
        }
    }
}

/** The empty state has no grid attached. Never await its first layout before starting a request. */
@Composable internal fun VodBrowseOnEntry(filter: VodFilter, search: String,
    scroll: androidx.compose.foundation.lazy.grid.LazyGridState, browse: () -> Unit) {
    val currentBrowse by rememberUpdatedState(browse)
    LaunchedEffect(filter, search) {
        scroll.requestScrollToItem(0)
        currentBrowse()
    }
}
