@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tvatlas.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

internal fun readVodSources(context: android.content.Context): List<Pair<String, String>> =
    Json.parseToJsonElement(context.assets.open("vod-test-sources.json").bufferedReader().use { it.readText() })
        .jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("api").jsonPrimitive.content }

@Composable internal fun VodCatalogPage(model: com.tvatlas.player.PlayerViewModel, onBack: () -> Unit, initialSource: String? = null, sourceRequest: Int = 0) {
    val library by model.library.collectAsState()
    val subscriptions by model.subscriptions.collectAsState()
    val route by model.vodRoute.collectAsState()
    val selecting by model.vodSelecting.collectAsState()
    var choosingNetwork by remember { mutableStateOf(false) }
    var choosingSource by remember { mutableStateOf(false) }
    val profile = library.profiles.firstOrNull { it.id == route.profile }
    val routeLabel = if (route.type == com.tvatlas.core.model.RouteType.DIRECT) "直连" else
        listOfNotNull(subscriptions.firstOrNull { it.id == profile?.subscriptionId }?.name, profile?.name ?: "节点不可用").joinToString(" · ")
    val context = LocalContext.current
    val sources = remember { readVodSources(context) }
    var selected by rememberSaveable(sourceRequest) { mutableIntStateOf(sources.indexOfFirst { it.second == initialSource }.coerceAtLeast(0)) }
    var page by remember { mutableStateOf<CatalogPage?>(null) }
    var movie by remember { mutableStateOf<CatalogMovie?>(null) }
    var returnFocusId by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeQuery by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var artworkLoader by remember { mutableStateOf<ImageLoader?>(null) }
    val scope = rememberCoroutineScope()
    val catalogScroll = rememberLazyGridState()
    var request by remember { mutableStateOf<Job?>(null) }
    var requestId by remember { mutableLongStateOf(0) }
    fun load(number: Int = 1, search: String = activeQuery, detail: CatalogMovie? = null) {
        val token = ++requestId
        if (detail == null) returnFocusId = null
        model.cloudNetworkAction {
            if (token != requestId) return@cloudNetworkAction
            request?.cancel()
            request = scope.launch {
                loading = true; error = null
                try {
                    val result = model.loadVodCatalog(sources[selected].second, number, search, detail?.id)
                    if (token != requestId) return@launch
                    if (detail != null) movie = result.movies.firstOrNull { it.id == detail.id }
                        ?: throw IllegalArgumentException("接口未返回这部影片的详情")
                    else { page = result; catalogScroll.scrollToItem(0) }
                    if (artworkLoader == null) artworkLoader = model.createVodArtworkLoader()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { if (token == requestId) error = if (e is IllegalArgumentException) e.message else "读取失败，请重试或切换来源" }
                finally { if (token == requestId) loading = false }
            }
        }
    }
    fun showList() { model.cancelVodSelection(); returnFocusId = movie?.id; ++requestId; request?.cancel(); movie = null; loading = false; error = null }
    fun selectSource(index: Int) {
        if (index == selected) return
        model.cancelVodSelection()
        ++requestId; request?.cancel(); selected = index; page = null; movie = null
        query = ""; activeQuery = ""; returnFocusId = null; artworkLoader = null; loading = false; error = null
    }
    LaunchedEffect(selected) { model.selectVodSource(sources[selected].second); load(search = activeQuery) }
    DisposableEffect(Unit) { onDispose { model.cancelVodSelection() } }
    BackHandler { if (movie != null) showList() else onBack() }
    DisposableEffect(artworkLoader) {
        val ownedLoader = artworkLoader
        onDispose { ownedLoader?.shutdown() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val wide = atlasTv() || maxWidth >= 720.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (wide) Column(Modifier.width(184.dp).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("影视", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("选择内容来源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sources.indices.toList(), key = { it }) { index ->
                        TvTextButton(onClick = { selectSource(index) }, modifier = Modifier.fillMaxWidth().selectionOutline(selected == index)) {
                            Text(sources[index].first, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                AtlasActionButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("网盘文件") }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (movie != null) TvIconButton(onClick = ::showList) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回片库") }
                    Column(Modifier.weight(1f)) {
                        Text(if (movie != null) "影片详情" else "影视片库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(sources[selected].first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (!wide) TvTextButton(onClick = { choosingSource = true }) { Text("换源") }
                    TvTextButton(onClick = { choosingNetwork = true }) { Text("网络") }
                    if (!wide) TvTextButton(onClick = onBack) { Text("网盘") }
                }
                if (loading || selecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (selecting) Text("正在为这一集自动选择可用线路…", style = MaterialTheme.typography.bodySmall)
                error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TvTextButton(onClick = { load(number = page?.page ?: 1, detail = movie) }) { Text("重试") }
                        }
                    }
                }
                val chosen = movie
                if (chosen != null) VodMovieDetails(chosen, artworkLoader, model.canLoadChannelArtwork(), loading || selecting,
                    modifier = Modifier.weight(1f), onPlay = { episode -> model.openVodChoice(episode, "${chosen.name} · ${episode.name}".take(80)) })
                else {
                    OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("搜索电影、剧集") }, shape = RoundedCornerShape(12.dp),
                        trailingIcon = { TvTextButton(onClick = { activeQuery = query.trim(); load(search = activeQuery) }, enabled = !loading) { Text("搜索") } })
                    if (page == null && !loading && error == null) {
                        MediaEmptyState("尚未加载片库", "点击读取，查看这个来源的影片。", "读取片库", { load() }, Modifier.weight(1f))
                    } else if (page?.movies?.isEmpty() == true && !loading) {
                        MediaEmptyState("没有找到影片", "试试其他关键词，或切换内容来源。", "清除搜索", { query = ""; activeQuery = ""; load(search = "") }, Modifier.weight(1f))
                    } else {
                        VodMovieGrid(page?.movies.orEmpty(), artworkLoader, model.canLoadChannelArtwork(), Modifier.weight(1f), catalogScroll, restoreFocusId = returnFocusId) { item ->
                            movie = item
                            if (item.episodes.isEmpty()) load(detail = item)
                        }
                    }
                    page?.let { result ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            TvTextButton(enabled = !loading && result.page > 1, onClick = { load(number = result.page - 1) }) { Text("上一页") }
                            Text("${result.page} / ${result.pages}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TvTextButton(enabled = !loading && result.page < result.pages, onClick = { load(number = result.page + 1) }) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }
    if (choosingSource) AlertDialog(onDismissRequest = { choosingSource = false }, title = { Text("内容来源") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sources.indices.toList()) { index ->
                TvTextButton(onClick = { selectSource(index); choosingSource = false }, modifier = Modifier.fillMaxWidth().selectionOutline(selected == index)) {
                    Text(sources[index].first)
                }
            }
        }
    }, confirmButton = { TvTextButton(onClick = { choosingSource = false }) { Text("关闭") } })
    if (choosingNetwork) AlertDialog(onDismissRequest = { choosingNetwork = false }, title = { Text("播放网络") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前：$routeLabel", style = MaterialTheme.typography.bodyMedium)
                Text("每个来源单独选择网络，默认直连。片单、海报与视频使用同一网络。", style = MaterialTheme.typography.bodySmall)
                if (model.isSystemVpnActive()) Text("系统 VPN 已开启，应用直连仍可能经过系统代理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                AtlasActionButton(onClick = { model.setVodRoute(com.tvatlas.core.model.RouteTarget.DIRECT); artworkLoader?.shutdown(); artworkLoader = null; choosingNetwork = false; load(number = page?.page ?: 1, detail = movie) }) { Text("直连") }
            } }
            items(library.profiles.filter { it.enabled }, key = { it.id }) { node ->
                val subscription = subscriptions.firstOrNull { it.id == node.subscriptionId }?.name
                TvTextButton(onClick = { model.setVodRoute(com.tvatlas.core.model.RouteTarget.proxy(node.id)); artworkLoader?.shutdown(); artworkLoader = null; choosingNetwork = false; load(number = page?.page ?: 1, detail = movie) }, modifier = Modifier.fillMaxWidth()) {
                    Text(listOfNotNull(subscription, node.name).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }, confirmButton = { TvTextButton(onClick = { choosingNetwork = false }) { Text("关闭") } })
}

@Composable internal fun VodMovieGrid(movies: List<CatalogMovie>, loader: ImageLoader?, allowNetwork: Boolean,
    modifier: Modifier = Modifier, state: LazyGridState = rememberLazyGridState(), restoreFocusId: String? = null, onSelect: (CatalogMovie) -> Unit) {
    val focus = remember { FocusRequester() }
    val tv = atlasTv()
    LaunchedEffect(restoreFocusId, tv) {
        if (tv && restoreFocusId != null) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.any { it.key == restoreFocusId } }.first { it }
            withFrameNanos { }
            // A source/page may be replaced between layout and focus restoration.
            runCatching { focus.requestFocus() }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val compact = tv || maxWidth >= 600.dp
    val columns = if (compact) (maxWidth.value / 112f).toInt().coerceIn(3, 7) else (maxWidth.value / 136f).toInt().coerceIn(2, 4)
    LazyVerticalGrid(columns = GridCells.Fixed(columns), state = state, modifier = Modifier.fillMaxSize().testTag("vod-movie-grid"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        gridItems(movies, key = { it.id }) { movie ->
            AtlasCard(onClick = { onSelect(movie) }, modifier = Modifier.fillMaxWidth().testTag("vod-movie:${movie.id}")
                    .then(if (tv && movie.id == restoreFocusId) Modifier.focusRequester(focus) else Modifier).tvFocusOutline(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                MoviePoster(movie, loader, allowNetwork, Modifier.fillMaxWidth().aspectRatio(if (compact) 1.1f else .85f))
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(movie.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(movie.category.ifBlank { "影片" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    }
}

@Composable private fun MoviePoster(movie: CatalogMovie, loader: ImageLoader?, allowNetwork: Boolean, modifier: Modifier) {
    val placeholder: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainerHigh))), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(movie.name, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Box(modifier) {
        if (movie.poster == null || loader == null) placeholder() else SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(movie.poster).size(360, 500)
                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(if (allowNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED).build(),
            imageLoader = loader, contentDescription = "${movie.name}海报", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), loading = { placeholder() }, error = { placeholder() })
    }
}

@Composable internal fun VodMovieDetails(movie: CatalogMovie, loader: ImageLoader?, allowNetwork: Boolean, loading: Boolean,
    modifier: Modifier = Modifier, onPlay: (VodEpisodeChoice) -> Unit) {
    val episodes = remember(movie) { VodEpisodeChoices.of(movie) }
    LazyVerticalGrid(columns = GridCells.Adaptive(116.dp), modifier = modifier.fillMaxWidth().testTag("vod-detail-content"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MoviePoster(movie, loader, allowNetwork, Modifier.width(104.dp).height(146.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(movie.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(movie.category.ifBlank { "影片" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Text(if (movie.episodes.isEmpty() && loading) "正在准备播放信息" else if (movie.episodes.isEmpty()) "暂无可用播放线路" else "共 ${episodes.size} 集 · 自动选择可用线路", style = MaterialTheme.typography.bodySmall)
                        episodes.firstOrNull()?.let { episode -> TvButton(onClick = { onPlay(episode) }, enabled = !loading) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Text("立即播放")
                        } }
                    }
                }
                if (movie.summary.isNotBlank()) Text(movie.summary, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (episodes.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("选集", style = MaterialTheme.typography.titleSmall)
                        Text("共 ${episodes.size} 集", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (!loading) Text("这个来源未提供可直接播放的地址，请返回片库换一部影片或切换来源。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        gridItems(episodes.withIndex().toList(), key = { it.index }) { (index, episode) ->
            AtlasActionButton(onClick = { onPlay(episode) }, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("vod-episode:$index")) {
                Text(episode.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun MediaEmptyState(title: String, description: String, action: String, onAction: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AtlasSymbols.Screen, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AtlasActionButton(onClick = onAction) { Text(action) }
        }
    }
}
