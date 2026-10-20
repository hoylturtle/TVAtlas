package com.tvatlas.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.tvatlas.player.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.tvatlas.core.model.Channel
import com.tvatlas.core.vod.*
import com.tvatlas.player.PlayerViewModel
import com.tvatlas.player.vod.HomeRecentVod
import com.tvatlas.player.vod.UnifiedVodState
import kotlinx.coroutines.*

@Composable internal fun HomePage(model: PlayerViewModel, onLive: (Channel) -> Unit,
    onLibrary: (VodKind) -> Unit, onSearch: () -> Unit) {
    val movies by model.homeMovies.state.collectAsStateWithLifecycle()
    val series by model.homeSeries.state.collectAsStateWithLifecycle()
    val history by model.homeHistory.state.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val options by model.options.collectAsStateWithLifecycle()
    val vod by model.vod.state.collectAsStateWithLifecycle()
    val selecting by model.vodSelecting.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<UnifiedMovie?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailJob by remember { mutableStateOf<Job?>(null) }
    var detailGeneration by remember { mutableLongStateOf(0) }
    var loader by remember { mutableStateOf<ImageLoader?>(null) }
    val scope = rememberCoroutineScope()
    val live = remember(library.channels, recent, options.favorites) {
        val byId = library.channels.associateBy { it.id }
        (library.channels.filter { it.id in options.favorites } + recent.mapNotNull { byId[it.channelId] } + library.channels)
            .distinctBy { it.id }.take(8)
    }
    val active = selected == null && vod.item == null && !selecting
    LaunchedEffect(active) {
        model.setHomePageActive(active)
        if (active) { withFrameNanos { }; model.refreshHomeAutomatically(); model.syncVodSubscriptions() }
    }
    LaunchedEffect(Unit) {
        try { loader = withContext(Dispatchers.IO) { model.createVodArtworkLoader() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Existing disk artwork remains available. */ }
    }
    DisposableEffect(Unit) { onDispose { model.setHomePageActive(false); detailJob?.cancel(); model.cancelVodSelection() } }
    DisposableEffect(loader) { val owned = loader; onDispose { owned?.shutdown() } }
    fun open(item: UnifiedMovie, episode: String? = null) {
        val token = ++detailGeneration
        detailJob?.cancel(); model.cancelVodSelection()
        selected = item; detailLoading = true; detailError = null
        model.cloudNetworkAction {
            if (token != detailGeneration || !scope.isActive) return@cloudNetworkAction
            detailJob = scope.launch {
                try {
                    val fresh = model.unifiedDetails(item)
                    if (token != detailGeneration) return@launch
                    selected = fresh
                    if (episode != null) {
                        val choice = VodEpisodeChoices.of(fresh.movie).firstOrNull {
                            VodEpisodeChoices.identity(it.name) == VodEpisodeChoices.identity(episode)
                        }
                        if (choice != null) model.openUnifiedChoice(choice, fresh.id + ":" + VodEpisodeChoices.identity(choice.name), fresh.movie.name + " · " + choice.name)
                        else detailError = "上次观看的集数暂不可用，请重新选集。"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (token == detailGeneration) detailError = "暂时无法读取播放信息，可重试或返回首页。" }
                finally { if (token == detailGeneration) detailLoading = false }
            }
        }
    }
    HomeScreen(movies, series, history, live, loader, model.canLoadChannelArtwork(),
        { item, episode -> open(item, episode) }, onLive, onLibrary, onSearch, model::refreshHome)
    selected?.let { item ->
        fun close() { detailGeneration++; detailJob?.cancel(); model.cancelVodSelection(); selected = null; detailLoading = false }
        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = AtlasPageStyle.canvas) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.movie.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TvTextButton(onClick = ::close) { Text("返回首页") }
                    }
                    if (detailLoading || selecting) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                    detailError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TvTextButton(onClick = { open(item) }) { Text("重试") }
                    }
                    VodMovieDetails(item.movie, loader, model.canLoadChannelArtwork(), detailLoading || selecting, Modifier.weight(1f)) { choice ->
                        model.openUnifiedChoice(choice, item.id + ":" + VodEpisodeChoices.identity(choice.name), item.movie.name + " · " + choice.name)
                    }
                }
            }
        }
    }
}

/** Native TV layout: metadata and static posters only; no video previews or player allocation. */
@Composable internal fun HomeScreen(movies: UnifiedVodState, series: UnifiedVodState,
    history: List<HomeRecentVod>, live: List<Channel>, loader: ImageLoader?, allowNetwork: Boolean,
    onMovie: (UnifiedMovie, String?) -> Unit, onLive: (Channel) -> Unit,
    onLibrary: (VodKind) -> Unit, onSearch: () -> Unit, onRefresh: () -> Unit) {
    val scroll = rememberLazyListState()
    val primaryFocus = remember { FocusRequester() }
    val tv = atlasTv()
    LaunchedEffect(Unit) {
        if (tv) {
            withFrameNanos { }
            if (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0) primaryFocus.requestFocus()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0D111A)).testTag("home-page")) {
        val compact = maxHeight < 420.dp
        val posterWidth = if (maxWidth >= 700.dp) ((maxWidth - 76.dp) / 4).coerceIn(160.dp, 300.dp) else 168.dp
        val wideHome = maxWidth >= 700.dp
        val posterHeight = if (compact) 64.dp else if (wideHome) (maxHeight * .13f).coerceIn(64.dp, 148.dp)
            else (posterWidth * .54f).coerceIn(80.dp, 128.dp)
        // Reserve room for two shelves, including two-line titles, before sizing the TV hero.
        val heroHeight = if (compact) 96.dp else if (wideHome)
            minOf((maxHeight * .32f).coerceIn(112.dp, 260.dp), (maxHeight - posterHeight * 2 - 232.dp).coerceIn(112.dp, 260.dp))
            else (maxHeight * .38f).coerceIn(200.dp, 310.dp)
        val condensedHero = compact || wideHome
        val hero = movies.items.firstOrNull() ?: series.items.firstOrNull() ?: history.firstOrNull()?.unified()
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("home-scroll"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    HomePoster(hero?.movie?.poster, loader, allowNetwork, Modifier.fillMaxSize(), large = true)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF0D111A), Color(0xFF0D111A).copy(alpha = .72f), Color.Transparent))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0D111A)))))
                    Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.72f).padding(start = 20.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(if (condensedHero) 6.dp else 10.dp)) {
                        if (!condensedHero) Text("发现好影片", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC7BDD9))
                        Text(hero?.movie?.name ?: "欢迎使用 TVAtlas", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
                            maxLines = if (condensedHero) 1 else 2, overflow = TextOverflow.Ellipsis, color = Color.White)
                        if (!compact) Text(hero?.movie?.let { listOf(it.category, it.year, it.area).filter(String::isNotBlank).joinToString(" · ") }
                            ?: "电影、剧集与直播，一处继续观看", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.LightGray)
                        TvTextButton(onClick = { if (hero != null) onMovie(hero, null) else onLibrary(VodKind.MOVIE) },
                            modifier = Modifier.focusRequester(primaryFocus).testTag("home-primary"),
                            colors = ButtonDefaults.textButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                            Text(if (hero == null) "浏览影视" else "查看详情")
                        }
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        TvIconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索影视") }
                        TvIconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "更新首页片单") }
                    }
                }
            }
            item(key = "latest-movies") {
                HomeMovieShelf("最新电影", movies, VodKind.MOVIE, posterWidth, posterHeight, loader, allowNetwork, onMovie, onLibrary)
            }
            item(key = "latest-series") {
                HomeMovieShelf("最新剧集", series, VodKind.SERIES, posterWidth, posterHeight, loader, allowNetwork, onMovie, onLibrary)
            }
            if (history.isNotEmpty()) item(key = "continue") {
                Text("继续观看", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(history, key = { it.movie.id }) { entry ->
                        HomeMovieCard(entry.unified(), posterWidth, posterHeight, loader, allowNetwork, "续播 · ${entry.episode}") { onMovie(entry.unified(), entry.episode) }
                    }
                }
            }
            item(key = "live") {
                Text("常看直播", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
                if (live.isEmpty()) Text("暂无频道，可在频道源中添加。", Modifier.padding(20.dp), color = Color.LightGray)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(live, key = { it.id }) { channel ->
                        Column(Modifier.width(posterWidth).height(118.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                            .tvFocusOutline().clickable { onLive(channel) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChannelArtwork(channel, allowNetwork = { allowNetwork }, modifier = Modifier.width(86.dp).height(42.dp))
                            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HomeMovieShelf(title: String, state: UnifiedVodState, kind: VodKind,
    width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, loader: ImageLoader?, allowNetwork: Boolean,
    onMovie: (UnifiedMovie, String?) -> Unit, onLibrary: (VodKind) -> Unit) {
    val tv = atlasTv()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text(if (state.showingCache) "缓存片单" else "按来源更新", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = Color(0xFFA9A3B5), maxLines = 1)
            TvTextButton(onClick = { onLibrary(kind) }, modifier = Modifier.height(if (tv) 36.dp else 48.dp)) { Text("查看全部") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp))
        if (state.items.isEmpty()) Text(if (state.loading) "正在读取片单…" else state.error ?: state.notice ?: "暂无可用片单，可点击右上角更新。",
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.items.take(12), key = { it.id }) { item -> HomeMovieCard(item, width, height, loader, allowNetwork) { onMovie(item, null) } }
        }
        if (state.items.isNotEmpty() && state.error != null) Text("暂时无法更新，已保留可用片单。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
    }
}

@Composable private fun HomeMovieCard(item: UnifiedMovie, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
    loader: ImageLoader?, allowNetwork: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Column(Modifier.width(width).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = .35f))
        .tvFocusOutline().clickable(onClick = onClick).testTag("home-movie:${item.id}")) {
        HomePoster(item.movie.poster, loader, allowNetwork, Modifier.fillMaxWidth().height(height))
        Text(item.movie.name, Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 4.dp).testTag("home-title:${item.id}"),
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
        if (subtitle != null) Text(subtitle, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HomePoster(url: String?, loader: ImageLoader?, allowNetwork: Boolean, modifier: Modifier, large: Boolean = false) {
    val context = LocalContext.current
    Box(modifier.background(Color(0xFF202939)), contentAlignment = Alignment.Center) {
        if (large) Image(painterResource(R.drawable.home_backdrop), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (url == null && !large) Icon(AtlasSymbols.Screen, null, Modifier.size(40.dp), tint = Color(0xFF6A6384))
        else if (url != null) SubcomposeAsyncImage(model = ImageRequest.Builder(context).data(url).size(if (large) 960 else 480, if (large) 540 else 270)
            .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(if (allowNetwork && loader != null) CachePolicy.ENABLED else CachePolicy.DISABLED).build(),
            imageLoader = loader ?: coil.Coil.imageLoader(context), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            loading = {}, error = { Icon(AtlasSymbols.Screen, null, tint = Color(0xFF6A6384)) })
    }
}
