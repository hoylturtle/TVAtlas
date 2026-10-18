@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tvatlas.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleCodec
import com.tvatlas.player.BuildConfig
import com.tvatlas.player.update.UpdateState
import com.tvatlas.player.update.REPOSITORY_URL
import androidx.compose.ui.text.AnnotatedString
import com.tvatlas.player.transfer.TransferKind
import com.tvatlas.player.PlayerViewModel
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Credentials
import com.tvatlas.player.storage.Library
import com.tvatlas.player.storage.SubscriptionRow
import java.text.DateFormat
import java.util.Date

internal val colors = darkColorScheme(primary = Color(0xFF8B5CF6), onPrimary = Color.White, primaryContainer = Color(0xFF6D28D9), onPrimaryContainer = Color.White,
    secondary = Color(0xFFD0B6FF), secondaryContainer = Color(0xFF6D28D9), onSecondaryContainer = Color.White,
    background = Color(0xFF171323), surface = Color(0xFF211C30), surfaceVariant = Color(0xFF342C47),
    surfaceContainerLowest = Color(0xFF171323), surfaceContainerLow = Color(0xFF211C30),
    surfaceContainer = Color(0xFF2A243B), surfaceContainerHigh = Color(0xFF342C47), surfaceContainerHighest = Color(0xFF403650),
    outline = Color(0xFF867A9C), outlineVariant = Color(0xFF4C415F),
    onBackground = Color(0xFFF5F3FF), onSurface = Color(0xFFF5F3FF), onSurfaceVariant = Color(0xFFA9A3B5),
    tertiary = Color(0xFFFFC857), error = Color(0xFFFF6B7A))

/** Establish foreground explicitly: a transparent Surface cannot infer it from its background. */
@Composable internal fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}

@Composable fun PlayerApp(model: PlayerViewModel) {
    val options by model.options.collectAsStateWithLifecycle()
    val applicationContext = LocalContext.current
    val systemTelevision = (applicationContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        applicationContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
        applicationContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)
    CompositionLocalProvider(LocalTvMode provides options.displayMode.television(systemTelevision)) {
    AtlasTheme {
        val pageMemory = rememberSaveableStateHolder()
        val channelMemory = rememberSaveableStateHolder()
        val library by model.library.collectAsStateWithLifecycle()
        val startup by model.startup.collectAsStateWithLifecycle()
        val status by model.playback.status.collectAsStateWithLifecycle()
        val livePlayer by model.playback.player.collectAsStateWithLifecycle()
        val activePlayback = status.loading || livePlayer != null
        val recent by model.recent.collectAsStateWithLifecycle()
        val pendingPlay by model.pendingPlay.collectAsStateWithLifecycle()
        val pendingDownload by model.pendingDownload.collectAsStateWithLifecycle()
        val pendingCloud by model.pendingCloudConsent.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val update by model.update.collectAsStateWithLifecycle()
        val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
        val updateRoute by model.updateRoute.collectAsStateWithLifecycle()
        val subscriptionRoute by model.subscriptionRoute.collectAsStateWithLifecycle()
        val subscriptionFeedback by model.subscriptionFeedback.collectAsStateWithLifecycle()
        val updatingSubscription by model.updatingSubscription.collectAsStateWithLifecycle()
        val playlistFeedback by model.playlistFeedback.collectAsStateWithLifecycle()
        val coreInfo by model.coreInfo.collectAsStateWithLifecycle()
        val nodeHealth by model.nodeHealth.collectAsStateWithLifecycle()
        val nodeTesting by model.nodeTesting.collectAsStateWithLifecycle()
        val nodeTestProgress by model.nodeTestProgress.collectAsStateWithLifecycle()
        val ruleFiles by model.ruleFiles.collectAsStateWithLifecycle()
        val subscriptions by model.subscriptions.collectAsStateWithLifecycle()
        CacheChannelArtwork(library.channels, model::canLoadChannelArtwork)
        val context = LocalContext.current
        val config = LocalConfiguration.current
        val tv = atlasTv()
        val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
        val wide = tv || (windowWidth >= 600.dp && config.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val landscape = !tv && windowWidth < 840.dp && config.orientation == Configuration.ORIENTATION_LANDSCAPE
        var mediaRequestedCatalog by rememberSaveable { mutableStateOf<Boolean?>(null) }
        var mediaRequestId by rememberSaveable { mutableIntStateOf(0) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        // Every navigation entry point shares this lifecycle, including restored pages and back.
        LaunchedEffect(tab) { model.setLivePageActive(tab == 0) }
        var channelSearchRequest by remember { mutableIntStateOf(0) }
        var cloudImportRequest by remember { mutableIntStateOf(0) }
        var exitPrompt by remember { mutableStateOf(false) }
        var liveMenuOpen by remember { mutableStateOf(false) }
        var pageMenuOpen by remember { mutableStateOf(false) }
        var apkUploadOpen by remember { mutableStateOf(false) }
        var logsOpen by rememberSaveable { mutableStateOf(false) }
        var fullscreen by rememberSaveable { mutableStateOf(false) }
        var channelPanelOpen by rememberSaveable { mutableStateOf(true) }
        var landscapeChannels by rememberSaveable { mutableStateOf(false) }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        var addPlaylist by rememberSaveable { mutableStateOf(false) }
        var addMediaSubscription by remember { mutableStateOf(false) }
        var editPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
        var deletePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
        var addProxy by rememberSaveable { mutableStateOf(false) }
        var addSubscription by rememberSaveable { mutableStateOf(false) }
        var removeSubscription by rememberSaveable { mutableStateOf<String?>(null) }
        var editProxyId by rememberSaveable { mutableStateOf<String?>(null) }
        val detail = library.channels.firstOrNull { it.id == detailId }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importRules) }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::exportRules) }
        var exportLogCategory by rememberSaveable { mutableStateOf(com.tvatlas.player.debug.LogCategory.ALL) }
        val logExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let { uri -> model.exportLogs(uri, exportLogCategory) } }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
        val immersive = tab == 0 && activePlayback && (fullscreen || (landscape && !landscapeChannels) || (wide && !channelPanelOpen))
        val theater = tab == 0 && wide
        BackHandler(tab == 0 && !exitPrompt && !liveMenuOpen) {
            when (liveBackAction(wide, immersive, activePlayback, channelPanelOpen)) {
                LiveBackAction.HIDE_CHANNELS -> { channelPanelOpen = false; fullscreen = true }
                LiveBackAction.SHOW_MENU -> liveMenuOpen = true
                LiveBackAction.SHOW_CHANNELS -> { fullscreen = false; landscapeChannels = true; channelPanelOpen = true }
                LiveBackAction.CONFIRM_EXIT -> exitPrompt = true
            }
        }
        BackHandler(tab != 0 && !liveMenuOpen) { if (wide) liveMenuOpen = true else tab = 0 }
        PlaybackOrientation(immersive || options.alwaysLandscape, tv && !options.alwaysLandscape)
        PlaybackSystemBars(immersive || tv || (theater && activePlayback))
        Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize().atlasBackdrop().then(if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
            contentWindowInsets = if (immersive) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!wide && !immersive && !theater) AtlasAppBar({ tab = 0; channelSearchRequest++ }, { tab = 3 }, busy)
            }, bottomBar = {
                if (!wide && !immersive && !theater) AtlasBottomNavigation(tab) { tab = it }
            }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).onPreviewKeyEvent { event ->
                if (wide && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                    if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                    if (tab == 0) liveMenuOpen = !liveMenuOpen
                    else pageMenuOpen = true
                    true
                } else false
            }) {
                MainNavigationRail(wide, immersive, activePlayback, tab, { tab = it; channelPanelOpen = true })
                Box(Modifier.weight(1f).fillMaxHeight().then(if (tab != 0) Modifier.background(AtlasPageStyle.canvas) else Modifier)) {
                CompositionLocalProvider(LocalTvMode provides (tv || wide)) {
                pageMemory.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> {
                        val video: @Composable (Modifier) -> Unit = { modifier ->
                            VideoPane(model, status, library, modifier, diagnostics, tv, tv && !channelPanelOpen, true, immersive || wide || tv, !tv && !wide && !immersive,
                                { if (immersive) {
                                    fullscreen = false; landscapeChannels = true; channelPanelOpen = true
                                } else {
                                    fullscreen = true; landscapeChannels = false; channelPanelOpen = false
                                } },
                                { channelPanelOpen = true; fullscreen = false; landscapeChannels = true },
                                { detailId = status.channelId }, { offset ->
                                    if (library.channels.isNotEmpty()) {
                                        val index = library.channels.indexOfFirst { it.id == status.channelId }.coerceAtLeast(0)
                                        model.play(library.channels[(index + offset + library.channels.size) % library.channels.size])
                                    }
                                }, panelLabel = if (wide) { if (immersive) "频道" else "收起" } else null)
                        }
                        val select: (Channel) -> Unit = {
                            if (status.channelId != it.id || model.playback.player.value == null) model.play(it)
                            if (wide) { channelPanelOpen = false }
                        }
                        val channels: @Composable (Modifier) -> Unit = { modifier ->
                            channelMemory.SaveableStateProvider("channel-browser") {
                                ChannelList(library, status, tv || wide || landscape, modifier, select, { detailId = it.id }, { tab = 1 }, options.favorites, recent.map { it.channelId }, model::toggleFavorite, { ProgrammeInfo(model.epg, it) }, autoExpand = true, allowArtworkNetwork = model::canLoadChannelArtwork, searchRequest = channelSearchRequest, guide = { ProgrammeInfo(model.epg, it, true) })
                            }
                        }
                        when {
                            library.channels.isEmpty() && !activePlayback -> StartupPage(startup, model::retryStartup, { tab = 1 }, { logsOpen = true })
                            immersive -> video(Modifier.fillMaxSize())
                            wide && !activePlayback -> LiveHomeLayout(
                                channelName = library.channels.firstOrNull { it.id == status.channelId }?.name,
                                message = status.message,
                                onReplay = { library.channels.firstOrNull { it.id == status.channelId }?.let { model.play(it, forceRestart = true) } },
                                channels = { channels(Modifier.fillMaxSize()) })
                            wide -> TvBrowseLayout(tv, library.channels.firstOrNull { it.id == status.channelId }?.name,
                                channels = { channels(Modifier.fillMaxSize()) },
                                picture = { video(Modifier.fillMaxSize()) },
                                onNavigation = { liveMenuOpen = true },
                                navigation = { dismiss -> TvNavigation(tab, { dismiss(); tab = it }, Modifier.fillMaxSize()) })
                            else -> MobileLiveLayout(status.channelId != null, Modifier.fillMaxSize(), picture = video,
                                channels = { channels(it) })
                        }
                    }
                    4 -> {
                        var catalogOpen by rememberSaveable { mutableStateOf(true) }
                        val controller = model.cloudLibrary
                        val cloudState by controller.state.collectAsStateWithLifecycle()
                        var consumedMediaRequest by rememberSaveable { mutableIntStateOf(-1) }
                        LaunchedEffect(mediaRequestId) {
                            if (consumedMediaRequest != mediaRequestId) {
                                mediaRequestedCatalog?.let { catalogOpen = it }
                                consumedMediaRequest = mediaRequestId
                            }
                        }
                        if (catalogOpen) UnifiedVodPage(model, { catalogOpen = false }) else CloudLibraryPage(cloudState, controller::importShare,
                            { share -> model.cloudNetworkAction { controller.open(share) } },
                            { file -> model.cloudNetworkAction { controller.enter(file) } }, controller::back,
                            { model.cloudNetworkAction { controller.refresh() } },
                            { model.cloudNetworkAction { controller.more() } }, controller::remove, model::openCloudWebsite, cloudImportRequest, model::openVod, { tab = 1 }, { catalogOpen = true })
                    }
                    1 -> {
                        val cloudCount = run {
                            val cloud by model.cloudLibrary.state.collectAsStateWithLifecycle()
                            cloud.shares.size
                        }
                        val vodFeedState by model.vodSubscriptions.state.collectAsStateWithLifecycle()
                        val sourceHealth by model.vodHealth.state.collectAsStateWithLifecycle()
                        val sourceChecks by model.recheckingVod.collectAsStateWithLifecycle()
                        val sourceRoute by model.vodRoute.collectAsStateWithLifecycle()
                        val sourceLabels by produceState<Map<String, String>>(emptyMap(), vodFeedState.sources, sourceHealth, sourceChecks, sourceRoute) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vodFeedState.sources.associate { it.api to model.vodHealthLabel(it.api) } }
                        }
                        LaunchedEffect(Unit) { model.syncVodSubscriptions() }
                        var configuringVodSource by remember { mutableStateOf<String?>(null) }
                        configuringVodSource?.let { source ->
                            VodSourceNetworkDialog(model, source) { configuringVodSource = null }
                        }
                        PlaylistPage(library, busy, { addPlaylist = true }, model::refresh, model::refreshBuiltIn, playlistFeedback,
                        { editPlaylistId = it.id }, model::enablePlaylist, { deletePlaylistId = it.id }, cloudCount,
                        { mediaRequestedCatalog = false; mediaRequestId++; tab = 4; cloudImportRequest++ },
                        { mediaRequestedCatalog = false; mediaRequestId++; tab = 4 },
                        vodSources = vodFeedState.sources.map { it.name to it.api },
                        vodSubscriptionStatus = vodFeedState.message, vodSubscriptionBusy = vodFeedState.busy,
                        onSyncVod = { model.syncVodSubscriptions(manual = true) }, vodHealth = sourceLabels, onRecheckVod = model::recheckVodSource,
                        mediaSubscriptions = vodFeedState.subscriptions, onAddMedia = { addMediaSubscription = true }, onRemoveMedia = model::removeMediaSubscription,
                        onBrowseVod = { _ -> mediaRequestedCatalog = true; mediaRequestId++; tab = 4 },
                        onConfigureVod = { api -> model.selectVodSource(api); configuringVodSource = api })
                    }
                    2 -> {
                        val target by model.playback.networkTarget.collectAsStateWithLifecycle()
                        val speed by model.playback.speed.collectAsStateWithLifecycle()
                        val usage by model.traffic.state.collectAsStateWithLifecycle()
                        RoutingPage(library, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        { exporter.launch("tvatlas-routes.json") }, { addProxy = true }, { editProxyId = it.id },
                        subscriptions, busy, { addSubscription = true }, model::refreshSubscription,
                        { removeSubscription = it }, model::enableNode, subscriptionRoute, model::setSubscriptionRoute,
                        subscriptionFeedback, updatingSubscription, coreInfo, model::refreshCoreInfo, { tab = 3; model.checkUpdate() },
                        nodeHealth, nodeTesting, model::testNodes, ruleFiles, model::selectRuleFile,
                        nodeTestProgress = nodeTestProgress, trafficContent = { TrafficStatistics(model.traffic, library.profiles, subscriptions) }, currentTarget = target, currentSpeed = speed, currentUsage = usage.entries.associate { it.id to it.total })
                    }
                    3 -> SettingsPage(diagnostics, model::showDiagnostics, update, model::checkUpdate, model::openUpdateDownload,
                        { logsOpen = true }, model::openRepository, library.profiles, updateRoute, model::setUpdateRoute, model::installUpdate, model::cancelUpdate,
                        trafficContent = { TrafficStatistics(model.traffic, library.profiles, subscriptions) }, playbackContent = { FeatureSettings(model, "playback") }, backupContent = { FeatureSettings(model, "backup") }, onUploadApk = { apkUploadOpen = true }, testingContent = {
                            TranslationSettings(model, returnToLive = { tab = 0 })
                        }, interfaceContent = { LandscapePreference(options.alwaysLandscape) { enabled -> model.editOptions { it.copy(alwaysLandscape = enabled) } }; DisplayModePicker(options.displayMode) { mode -> model.editOptions { it.copy(displayMode = mode) } } })
                }
                }
                }
                }
            }
        }
        LaunchedEffect(tab, immersive, liveMenuOpen) {
            model.debugLog.event("INFO", "NAVIGATION", "page=$tab fullscreen=$immersive mainMenu=$liveMenuOpen")
        }
        if (liveMenuOpen) LiveNavigationDrawer(onDismiss = { liveMenuOpen = false },
            onExit = { liveMenuOpen = false; exitPrompt = true }, focusReturn = false,
            returnLabel = if (tab == 0) "返回直播" else "返回当前页面") { dismiss ->
            TvNavigation(tab, { dismiss(); tab = it; channelPanelOpen = true; fullscreen = false }, Modifier.fillMaxSize(), requestSelectedFocus = true)
        }
        if (pageMenuOpen) AlertDialog(onDismissRequest = { pageMenuOpen = false }, title = { Text("页面操作") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == 1) TvTextButton(onClick = { pageMenuOpen = false; addPlaylist = true }) { Text("添加频道源 / 手机扫码") }
                if (tab == 2) TvTextButton(onClick = { pageMenuOpen = false; addSubscription = true }) { Text("添加代理订阅") }
                if (tab == 4) TvTextButton(onClick = { pageMenuOpen = false; tab = 1 }) { Text("管理来源") }
                if (tab == 3) TvTextButton(onClick = { pageMenuOpen = false; logsOpen = true }) { Text("调试日志") }
                TvTextButton(onClick = { pageMenuOpen = false; tab = 0 }) { Text("返回直播") }
                TvTextButton(onClick = { pageMenuOpen = false; exitPrompt = true }) { Text("退出应用") }
            }
        }, confirmButton = { TvTextButton(onClick = { pageMenuOpen = false }) { Text("关闭") } })
        if (exitPrompt) ExitConfirmation({ exitPrompt = false }) { model.stopPlayback(); (context as? android.app.Activity)?.finish() }
        run {
            val vod by model.vod.state.collectAsStateWithLifecycle()
            val nextEpisode by model.nextVodEpisode.collectAsStateWithLifecycle()
            val preparingVod by model.vodSelecting.collectAsStateWithLifecycle()
            if (vod.item != null) VodPlaybackDialog(vod, model.vod, model::toggleVod, model::restartVod,
                nextEpisode, preparingVod, options.vodAutoNext, { enabled -> model.editOptions { it.copy(vodAutoNext = enabled) } }, model::advanceVodEpisode)
        }
        if (pendingPlay != null || pendingDownload || pendingCloud) AlertDialog(onDismissRequest = model::dismissMetered, title = { Text("当前为移动数据网络") },
            text = { Text(if (pendingDownload) "下载 APK 会消耗移动数据。确认继续？" else "播放及网盘访问可能消耗较多移动数据。继续后，本次运行允许使用移动数据。") },
            confirmButton = { TvTextButton(onClick = model::approveMetered) { Text("继续播放") } },
            dismissButton = { TvTextButton(onClick = model::dismissMetered) { Text("取消") } })
        if (apkUploadOpen) ApkUploadDialog { apkUploadOpen = false }
        if (logsOpen) {
            val logLines by model.debugLog.lines.collectAsStateWithLifecycle()
            DebugLogDialog(logLines, { model.debugLog.snapshot() }, { logsOpen = false }, model::clearLogs,
                { logExporter.launch("tvatlas-debug-v${BuildConfig.VERSION_NAME}.txt") },
                categorySnapshot = { model.debugLog.snapshot(it) }, categoryLines = { model.debugLog.categoryLines(it) },
                categoryExport = { category -> exportLogCategory = category; logExporter.launch("tvatlas-${category.fileKey}-v${BuildConfig.VERSION_NAME}.txt") })
        }
        if (addMediaSubscription) MediaSubscriptionDialog({ addMediaSubscription = false }) { name, url ->
            addMediaSubscription = false; model.addMediaSubscription(name, url)
        }
        if (addPlaylist) PlaylistDialog(busy, { addPlaylist = false }) { name, url -> model.addPlaylist(name, url); addPlaylist = false }
        val editPlaylist = library.playlists.firstOrNull { it.id == editPlaylistId }
        if (editPlaylist != null) PlaylistDialog(busy, { editPlaylistId = null }, editPlaylist) { name, url ->
            model.editPlaylist(editPlaylist, name, url); editPlaylistId = null
        }
        if (deletePlaylistId != null) AlertDialog(onDismissRequest = { deletePlaylistId = null }, title = { Text("删除频道源？") },
            text = { Text("仅移除此来源的线路，共享频道的其他来源会保留。") },
            confirmButton = { TvTextButton(onClick = { model.deletePlaylist(deletePlaylistId!!); deletePlaylistId = null }) { Text("删除") } },
            dismissButton = { TvTextButton(onClick = { deletePlaylistId = null }) { Text("取消") } })
        if (addSubscription) SubscriptionDialog(busy, { addSubscription = false }) { name, url ->
            model.addSubscription(name, url); addSubscription = false
        }
        if (removeSubscription != null) AlertDialog(onDismissRequest = { removeSubscription = null },
            title = { Text("删除订阅？") }, text = { Text("将移除该订阅及其节点。引用这些节点的手动路由需要重新选择。") },
            confirmButton = { TvTextButton(onClick = { model.deleteSubscription(removeSubscription!!); removeSubscription = null }) { Text("删除") } },
            dismissButton = { TvTextButton(onClick = { removeSubscription = null }) { Text("取消") } })
        if (addProxy || editProxyId != null) ProxyDialog(library.profiles.firstOrNull { it.id == editProxyId },
            { addProxy = false; editProxyId = null }) { p, credentials, replace ->
            model.saveProxy(p, credentials, replace); addProxy = false; editProxyId = null
        }
        if (detail != null) ChannelDialog(detail, library.profiles, library.playlists, status, { detailId = null },
            { streamId, target -> model.setRoute(detail.id, streamId, target) },
            { streamId -> model.play(detail, streamId); detailId = null }, { model.resetChannelRoutes(detail.id) }, busy, favorite = {
                TvTextButton(onClick = { model.toggleFavorite(detail) }, modifier = Modifier.fillMaxWidth().selectionOutline(detail.id in options.favorites)) { Text(if (detail.id in options.favorites) "取消收藏" else "收藏频道") } }, history = {
                val observations by model.playback.routeRecords.collectAsStateWithLifecycle()
                RouteRecordSummary(detail, library, subscriptions, observations)
            })
    }
}
}

@Composable internal fun ChannelList(library: Library, status: PlaybackStatus, tv: Boolean, modifier: Modifier,
    onPlay: (Channel) -> Unit, onDetails: (Channel) -> Unit, onAdd: () -> Unit,
    favorites: Set<String> = emptySet(), recent: List<String> = emptyList(), onFavorite: (Channel) -> Unit = {},
    programme: @Composable (Channel) -> Unit = {}, autoExpand: Boolean = false,
    allowArtworkNetwork: () -> Boolean = { false }, searchRequest: Int = 0, guide: @Composable (Channel) -> Unit = programme) {
    if (tv) {
        TvChannelBrowser(library, status, modifier, onPlay, onDetails, onAdd, favorites, recent, onFavorite, programme, allowArtworkNetwork, searchRequest, guide)
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var selectedRegion by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { searchOpen = true; mode = 0 } }
    val channelIndex = rememberChannelBrowseIndex(library.channels)
    LaunchedEffect(autoExpand, channelIndex) {
        if (autoExpand && !initialized && channelIndex != null && channelIndex.channels.isNotEmpty()) {
            val first = library.channels.firstOrNull { it.id == status.channelId }
                ?: channelIndex.channels.first()
            selectedRegion = channelIndex.region(first)
            expanded = listOf("region:${channelIndex.region(first)}", "type:${channelIndex.region(first)}:${channelIndex.type(first)}")
            initialized = true
        }
    }
    val firstFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    var lastFocusedChannel by rememberSaveable { mutableStateOf<String?>(null) }
    val listPosition = rememberLazyListState()
    val inputMode = LocalInputModeManager.current
    if (channelIndex == null) { ChannelBrowseLoading(modifier); return }
    val sortedChannels = channelIndex.channels
    Column(modifier.padding(horizontal = 8.dp)) {
        if (autoExpand && library.channels.isNotEmpty()) {
            val regions = channelIndex.regions
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    regions.forEachIndexed { index, region ->
                        AtlasChip(region, region == (selectedRegion ?: regions.first()), {
                            selectedRegion = region
                            val firstType = library.channels.first { channelIndex.region(it) == region }.browseType()
                            expanded = listOf("region:$region", "type:$region:$firstType")
                        }, if (tv && index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                }
                TextButton(onClick = { searchOpen = !searchOpen }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(48.dp)) { Text(if (searchOpen) "收起" else "搜索", style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (!autoExpand || searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索频道") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "收藏", "最近").forEachIndexed { index, label ->
                AtlasChip(label, mode == index, { mode = index; collapsed = emptyList() }, Modifier.weight(1f))
            }
        }
        if (library.channels.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("从你的播放列表开始", style = MaterialTheme.typography.titleLarge)
                Text("支持 HTTP(S) M3U / M3U8", Modifier.padding(12.dp))
                TvButton(onClick = onAdd) { Text("添加播放列表") }
            }
        } else {
            val filtered = remember(channelIndex, query, mode, favorites, recent, selectedRegion, autoExpand) {
                val items = sortedChannels.filter { (it.name.contains(query, true) || it.group.contains(query, true) || channelIndex.region(it).contains(query, true) || channelIndex.type(it).contains(query, true)) &&
                    (mode == 0 || mode == 1 && it.id in favorites || mode == 2 && it.id in recent) &&
                    (!autoExpand || mode != 0 || query.isNotBlank() || channelIndex.region(it) == (selectedRegion ?: channelIndex.regions.firstOrNull())) }
                if (mode == 2) { val positions = recent.withIndex().associate { it.value to it.index }; items.sortedBy { positions[it.id] ?: Int.MAX_VALUE } } else items
            }
            Text("${filtered.size} 个频道" + if (filtered.size != library.channels.size) " / 共 ${library.channels.size} 个" else "", Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            if (filtered.isEmpty()) Text(if (mode == 1) "尚无收藏频道" else if (mode == 2) "尚无成功观看记录" else "没有匹配频道", style = MaterialTheme.typography.bodySmall)
            val grouped = remember(channelIndex, filtered, mode) { filtered.groupBy { channelIndex.region(it) }.mapValues { (_, channels) -> channels.groupBy { channelIndex.type(it) }.mapValues { (_, items) -> if (mode == 2) items else channelIndex.primaryFirst(items) } } }
            fun open(key: String) = query.isNotBlank() || if (mode != 0) key !in collapsed else key in expanded
            fun toggle(key: String) {
                if (query.isNotBlank()) return
                if (mode != 0) collapsed = if (key in collapsed) collapsed - key else collapsed + key
                else expanded = if (key in expanded) expanded - key else expanded + key
            }
            LazyColumn(Modifier.fillMaxSize().testTag("channel-list"), state = listPosition, contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                grouped.forEach { (region, types) ->
                    val regionKey = "region:$region"
                    if (!autoExpand || mode != 0 || query.isNotBlank()) item(key = regionKey) {
                        TvTextButton(onClick = { toggle(regionKey) },
                            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (open(regionKey)) "已展开" else "已收起" }
                                .then(if (tv) Modifier.tvFocusOutline() else Modifier)
                                .then(if (tv && region == grouped.keys.firstOrNull()) Modifier.focusRequester(firstFocus) else Modifier)) {
                            Text("${if (open(regionKey)) "⌄" else "›"}  $region", Modifier.weight(1f), color = if (tv) LocalContentColor.current else MaterialTheme.colorScheme.secondary)
                            Text("${types.values.sumOf { it.size }}")
                        }
                    }
                    if (autoExpand || open(regionKey)) types.forEach { (type, channels) ->
                        val typeKey = "type:$region:$type"
                        item(key = typeKey) {
                            TvTextButton(onClick = { toggle(typeKey) }, modifier = Modifier.fillMaxWidth().padding(start = if (autoExpand) 0.dp else 16.dp)
                                .semantics { stateDescription = if (open(typeKey)) "已展开" else "已收起" }
                                .then(if (tv) Modifier.tvFocusOutline() else Modifier)) {
                                Text("${if (open(typeKey)) "⌄" else "›"}  $type", Modifier.weight(1f))
                                Text("${channels.size}")
                            }
                        }
                        if (open(typeKey)) items(channels, key = { it.id }) { channel ->
                        var focused by remember { mutableStateOf(false) }
                        ListItem(headlineContent = { Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (status.channelId == channel.id) FontWeight.Bold else FontWeight.Normal) },
                            leadingContent = { ChannelArtwork(channel, status.channelId == channel.id && status.successAt != null, allowArtworkNetwork) },
                            supportingContent = { programme(channel) },
                            trailingContent = { TvTextButton(onClick = { onFavorite(channel) }) {
                                Text(if (channel.id in favorites) "★" else "☆", color = if (status.channelId == channel.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.semantics { contentDescription = if (channel.id in favorites) "取消收藏 ${channel.name}" else "收藏 ${channel.name}" }) } },
                            colors = ListItemDefaults.colors(containerColor = if (status.channelId == channel.id) MaterialTheme.colorScheme.primary else if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer, headlineColor = if (status.channelId == channel.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                supportingColor = if (status.channelId == channel.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.testTag("channel:${channel.id}").padding(start = if (autoExpand) 0.dp else 16.dp, bottom = 2.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).then(if (channel.id == lastFocusedChannel) Modifier.focusRequester(channelFocus) else Modifier)
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) lastFocusedChannel = channel.id }
                                .border(3.dp, if (focused) Color.White else Color.Transparent)
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.keyCode in listOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER) &&
                                        event.type == KeyEventType.KeyDown && event.nativeKeyEvent.isLongPress) { onDetails(channel); true } else false
                                }.combinedClickable(onClick = { onPlay(channel) }, onLongClick = { onDetails(channel) }))
                    }
                    }
                }
            }
        }
    }
    LaunchedEffect(tv, library.channels.isNotEmpty()) {
        if (tv && library.channels.isNotEmpty()) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { } // LazyColumn group buttons must be laid out before focus is requested.
            val visible = lastFocusedChannel != null && listPosition.layoutInfo.visibleItemsInfo.any { it.key == lastFocusedChannel }
            val restored = visible && runCatching { channelFocus.requestFocus() }.getOrDefault(false)
            if (!restored) runCatching { firstFocus.requestFocus() }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoPane(model: PlayerViewModel, status: PlaybackStatus, library: Library, modifier: Modifier, diagnostics: Boolean, tv: Boolean, pictureFocused: Boolean, fillPicture: Boolean, immersive: Boolean, compact: Boolean,
    onFullscreen: () -> Unit, onShowChannels: () -> Unit, onDetails: () -> Unit, onChannel: (Int) -> Unit, panelLabel: String? = null) {
    val player by model.playback.player.collectAsStateWithLifecycle()
    val translation by model.subtitleTranslation.state.collectAsStateWithLifecycle()
    var translationOpen by remember { mutableStateOf(false) }
    val networkTarget by model.playback.networkTarget.collectAsStateWithLifecycle()
    val subscriptions by model.subscriptions.collectAsStateWithLifecycle()
    var tracksOpen by remember { mutableStateOf(false) }
    var subtitlesOpen by remember { mutableStateOf(false) }
    var subtitlesEnabled by remember(player) { mutableStateOf(player?.trackSelectionParameters?.disabledTrackTypes?.contains(androidx.media3.common.C.TRACK_TYPE_TEXT) == false) }
    var hasSubtitleTracks by remember(player) { mutableStateOf(player?.currentTracks?.groups?.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT } == true) }
    var subtitleSelected by remember(player) { mutableStateOf(player?.currentTracks?.groups?.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected } == true) }
    var subtitleCues by remember(player) { mutableStateOf(player?.currentCues?.cues?.isNotEmpty() == true) }
    var subtitleReady by remember(player) { mutableStateOf(player?.playbackState == androidx.media3.common.Player.STATE_READY) }
    DisposableEffect(player) {
        val current = player
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                hasSubtitleTracks = tracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && (0 until it.length).any(it::isTrackSupported) }
                subtitleSelected = tracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected }
            }
            override fun onCues(group: androidx.media3.common.text.CueGroup) { subtitleCues = group.cues.isNotEmpty() }
            override fun onPlaybackStateChanged(state: Int) { subtitleReady = state == androidx.media3.common.Player.STATE_READY }
            override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                subtitlesEnabled = androidx.media3.common.C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes
            }
        }
        current?.addListener(listener)
        onDispose { current?.removeListener(listener) }
    }
    LaunchedEffect(player) { tracksOpen = false; subtitlesOpen = false }
    val replay = { library.channels.firstOrNull { it.id == status.channelId }?.let { model.play(it, status.selectedStreamId, forceRestart = true) }; Unit }
    val picture: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            if (player == null) AtlasIdlePicture(if (compact && status.error != null && !status.loading) "播放未成功" else status.message,
                library.channels.firstOrNull { it.id == status.channelId }, status.loading)
            else AndroidView(factory = { PlayerView(it).apply { resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT; useController = !tv && !immersive; keepScreenOn = true; isFocusable = !immersive && (!tv || pictureFocused); isFocusableInTouchMode = !immersive && (!tv || pictureFocused); if (pictureFocused && !immersive) post { requestFocus() } } }, onReset = null, update = { it.player = player; it.subtitleView?.visibility = if (translation.enabled) android.view.View.GONE else android.view.View.VISIBLE; it.useController = !tv && !immersive; it.isFocusable = !immersive && (!tv || pictureFocused); it.isFocusableInTouchMode = !immersive && (!tv || pictureFocused); if (pictureFocused && !immersive && !it.hasFocus()) it.post { it.requestFocus() } },
                onRelease = { it.player = null }, modifier = Modifier.fillMaxSize().focusProperties { canFocus = !immersive && (!tv || pictureFocused) }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> { onChannel(-1); true }
                        Key.DirectionDown -> { onChannel(1); true }
                        Key.DirectionLeft -> if (tv) { onShowChannels(); true } else false
                        Key.DirectionCenter, Key.Enter -> if (event.nativeKeyEvent.isLongPress) { onDetails(); true } else if (tv) { onShowChannels(); true } else false
                        else -> false
                    }
                })
            if (translation.enabled && translation.original.isNotBlank()) {
                val text = if (translation.translated.isBlank()) translation.original else if (translation.bilingual)
                    translation.original + "\n" + translation.translated else translation.translated
                Text(text, Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 88.dp)
                    .background(Color.Black.copy(alpha = .7f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(10.dp),
                    color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 4,
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
        }
    val controls: @Composable () -> Unit = {
        if (tv && pictureFocused && !immersive) Text("画面 ↑↓ 换台 · 确认 频道列表 · 操作栏 ↑ 收起 · 长按确认 线路 · 返回 主菜单", Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        val displayedAttempt = status.attempt ?: status.lastAttempt
        VideoControls(if (compact && status.error != null && !status.loading) "播放未成功" else if (status.attempt != null && status.successAt == null && status.attemptNumber > 0) "第${status.attemptNumber}次／最多${status.candidateCount}条 · ${status.message}" else status.message, displayedAttempt?.let { attempt ->
            library.channels.firstOrNull { it.id == status.channelId }?.let { streamName(it, attempt.streamId, library.playlists) }
        } ?: "线路", playbackRouteLabel(networkTarget ?: displayedAttempt?.target, library.profiles, subscriptions),
            status.channelId != null, player != null || status.loading, onDetails, onFullscreen, model::stopPlayback,
            canReplay = player == null && !status.loading && status.channelId != null, onReplay = replay,
            fullscreenLabel = panelLabel ?: if (tv) "频道" else if (immersive) "退出全屏" else "全屏 / 频道", immersive = immersive,
            tail = {
                PlaybackNetworkInfo(model, networkTarget ?: displayedAttempt?.target)
                TvTextButton(onClick = { translationOpen = true }, enabled = player != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("翻译", maxLines = 1) }
                TvTextButton(onClick = { subtitlesOpen = true }, enabled = player != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(when {
                        !subtitlesEnabled -> "字幕：关"
                        !subtitleReady -> "字幕：检测中"
                        !hasSubtitleTracks -> "字幕：无轨道"
                        !subtitleSelected -> "字幕：待选择"
                        subtitleCues -> "字幕：显示中"
                        else -> "字幕：已开启"
                    }, maxLines = 1) }
                if (immersive) TvTextButton(onClick = { tracksOpen = true }, enabled = player != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("画质", maxLines = 1) }
            }, fullscreenRoute = playbackRouteLabel(networkTarget ?: displayedAttempt?.target, library.profiles, subscriptions),
            channel = library.channels.firstOrNull { it.id == status.channelId }, artworkNetwork = model::canLoadChannelArtwork)

        if (!immersive && !compact && player == null && !status.loading && status.error != null) PlaybackRecoveryCard(status.error,
            onRetry = replay, onChooseRoute = onDetails)
        val diagnosticAttempt = status.attempt ?: status.lastAttempt
        val diagnosticText = diagnosticAttempt?.let { "${redactedUrl(it.streamUrl)}\n${routeLabel(it.target, library.profiles)} · ${it.matchedRule}" }.orEmpty()
        val settledError = status.error?.takeIf { !status.loading && player == null }
        if (!immersive && compact && (settledError != null || (diagnostics && diagnosticAttempt != null))) {
            CompactPlaybackDetails(settledError, status.message + if (diagnostics && diagnosticText.isNotBlank()) "\n$diagnosticText" else "")
        } else if (!immersive && diagnostics && diagnosticAttempt != null) Text(diagnosticText +
            (if (player != null) status.error?.let { "\n$it" } ?: "" else ""), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    if (immersive) ImmersivePlaybackLayout(player != null || status.loading, tv, modifier, status.channelId,
        onChannel, onDetails, picture, controls, keepControlsVisible = panelLabel == "收起", onShowChannels = onShowChannels, acceptRemoteFocus = !tv || pictureFocused)
    else PlayerPaneLayout(fillPicture, modifier, picture, controls)
    if (translationOpen) AlertDialog(onDismissRequest = { translationOpen = false }, title = { Text("字幕翻译") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { TranslationSettings(model, livePictureVisible = true) } },
        confirmButton = { TvTextButton(onClick = { translationOpen = false }) { Text("关闭") } })
    if (tracksOpen && player != null) PlaybackTrackDialog(requireNotNull(player),
        onTextEnabledChanged = { enabled -> model.editOptions { it.copy(liveSubtitles = enabled) } }) { tracksOpen = false }
    if (subtitlesOpen && player != null) PlaybackTrackDialog(requireNotNull(player), onlyType = androidx.media3.common.C.TRACK_TYPE_TEXT,
        onTextEnabledChanged = { enabled -> model.editOptions { it.copy(liveSubtitles = enabled) } }) { subtitlesOpen = false }
}


@Composable internal fun PlayerPaneLayout(fillPicture: Boolean, modifier: Modifier = Modifier,
    picture: @Composable () -> Unit, controls: @Composable () -> Unit) {
    Column(modifier.background(Color.Black)) {
        val viewport = if (fillPicture) Modifier.weight(1f).fillMaxWidth()
            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        Box(viewport, contentAlignment = Alignment.Center) { picture() }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) { controls() }
    }
}

@Composable internal fun VideoControls(status: String, line: String, proxy: String,
    canDetails: Boolean, canStop: Boolean, onDetails: () -> Unit, onFullscreen: () -> Unit, onStop: () -> Unit,
    canReplay: Boolean = false, onReplay: () -> Unit = {}, fullscreenLabel: String = "全屏 / 频道",
    immersive: Boolean = false, fullscreenRoute: String = proxy, channel: Channel? = null, artworkNetwork: () -> Boolean = { false }, tail: @Composable () -> Unit = {}) {
    if (immersive) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            channel?.let { ChannelArtwork(it, allowNetwork = artworkNetwork, modifier = Modifier.width(50.dp).height(40.dp), compactLogo = true) }
            TvTextButton(onClick = onDetails, enabled = canDetails, modifier = Modifier.weight(1f).testTag("video-route-button"),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(listOfNotNull(channel?.name, line.takeIf { it.isNotBlank() }).distinct().joinToString(" · "),
                        color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fullscreenRoute, color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (fullscreenLabel == "收起" && LocalConfiguration.current.screenWidthDp >= 1100) {
                Text("上下选择 · 确认播放 · 右键节目单", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            tail()
            TvTextButton(onClick = onFullscreen, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(fullscreenLabel, color = LocalContentColor.current, maxLines = 1) }
            TvTextButton(onClick = if (canReplay) onReplay else onStop, enabled = canStop || canReplay,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Text(if (canReplay) "重新播放" else "停止", color = LocalContentColor.current, maxLines = 1)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(proxy.ifBlank { status }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        tail()
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTextButton(onClick = onDetails, enabled = canDetails, modifier = Modifier.weight(1f)) {
            Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TvTextButton(onClick = onFullscreen, modifier = Modifier.weight(1f)) { Text(fullscreenLabel, maxLines = 1) }
        TvTextButton(onClick = if (canReplay) onReplay else onStop, enabled = canStop || canReplay, modifier = Modifier.weight(1f)) {
            Text(if (canReplay) "重新播放" else "停止", maxLines = 1)
        }
    }
}

@Composable internal fun PlaylistDialog(busy: Boolean, onDismiss: () -> Unit, initial: Playlist? = null, onSave: (String, String) -> Unit) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var url by rememberSaveable(initial?.id) { mutableStateOf(initial?.url.orEmpty()) }
    var scan by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf(false) }
    if (scan) {
        PhoneTransferDialog(TransferKind.PLAYLIST, { scan = false }) {
            if (it.name.isNotBlank()) name = it.name else if (name.isBlank()) name = TransferKind.PLAYLIST.defaultName
            url = it.url; received = true; scan = false
        }
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "添加播放列表" else "编辑频道源") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("HTTP(S) M3U 地址") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = url.isNotEmpty() && httpUri(url.trim()) == null)
            TvOutlinedButton(onClick = { scan = true }, enabled = !busy) { Text("手机扫码输入") }
            if (received) Text("手机地址已接收，请确认后加载并保存。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TvTextButton(onClick = { onSave(name, url) }, enabled = !busy && name.isNotBlank() && httpUri(url.trim()) != null) { Text("加载并保存") } },
        dismissButton = { TvTextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun ProxyDialog(existing: ProxyProfile?, onDismiss: () -> Unit, onSave: (ProxyProfile, Credentials?, Boolean) -> Unit) {
    var id by remember { mutableStateOf(existing?.id.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.toString().orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: ProxyType.HTTP) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var clearCredentials by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加代理" else "编辑代理") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(id, { id = it }, label = { Text("ID，例如 US / HK") }, singleLine = true, enabled = existing == null)
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            Row { ProxyType.entries.filter { it != ProxyType.MIHOMO }.forEach { value -> FilterChip(selected = type == value, onClick = { type = value }, label = { Text(value.name) }, modifier = Modifier.padding(end = 8.dp)) } }
            OutlinedTextField(host, { host = it }, label = { Text("主机或 IP") }, singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Text("启用代理") }
            Text(if (existing == null) "需要认证时填写用户名和密码" else "留空保留已有凭证；填写后替换", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(username, { username = it }, label = { Text("用户名（可选）") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("密码（可选）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearCredentials, { clearCredentials = it }); Text("清除已有凭证") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TvTextButton(onClick = {
        val profile = ProxyProfile(id.trim(), name.trim(), type, host.trim(), port.toIntOrNull() ?: 0, enabled)
        val valid = runCatching { RuleCodec.validateProxy(profile); require(clearCredentials || (username.isBlank() == password.isBlank())) { "用户名和密码需同时填写" } }
        if (valid.isSuccess) onSave(profile, if (clearCredentials || username.isBlank()) null else Credentials(username, password),
            existing == null || clearCredentials || username.isNotBlank()) else error = valid.exceptionOrNull()?.message
    }) { Text("保存") } }, dismissButton = { TvTextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable internal fun ChannelDialog(channel: Channel, profiles: List<ProxyProfile>, playlists: List<Playlist>, status: PlaybackStatus, onDismiss: () -> Unit,
    onRoute: (String?, RouteTarget?) -> Unit, onPlay: (String?) -> Unit,
    onResetRoutes: () -> Unit = { onRoute(null, null); channel.streams.forEach { onRoute(it.id, null) } }, busy: Boolean = false, favorite: @Composable () -> Unit = {}, history: @Composable () -> Unit = {}) {
    var selectedStreamId by remember(channel.id) {
        mutableStateOf((status.attempt ?: status.lastAttempt)?.takeIf { status.channelId == channel.id }?.streamId)
    }
    val selectedStream = channel.streams.firstOrNull { it.id == selectedStreamId }
    var advanced by remember(channel.id) { mutableStateOf(false) }
    var historyOpen by remember(channel.id) { mutableStateOf(false) }
    val availableHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(channel.name) }, text = {
        Column(Modifier.heightIn(max = (availableHeight - 180.dp).coerceIn(120.dp, 420.dp)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            favorite()
            Text("播放源", style = MaterialTheme.typography.labelLarge)
            var lineMenu by remember { mutableStateOf(false) }
            Box {
                TvOutlinedButton(onClick = { lineMenu = true }, enabled = channel.streams.isNotEmpty()) {
                    Text(selectedStream?.let { streamName(channel, it.id, playlists) } ?: "自动选线")
                }
                DropdownMenu(expanded = lineMenu, onDismissRequest = { lineMenu = false }) {
                    DropdownMenuItem(modifier = Modifier.selectionOutline(selectedStreamId == null), text = { Text("自动选线") }, onClick = { selectedStreamId = null; lineMenu = false })
                    channel.streams.forEach { stream ->
                        DropdownMenuItem(modifier = Modifier.selectionOutline(selectedStreamId == stream.id), text = { Text(streamName(channel, stream.id, playlists)) }, onClick = { selectedStreamId = stream.id; lineMenu = false })
                    }
                }
            }
            Text("频道代理", style = MaterialTheme.typography.labelLarge)
            RoutePicker(profiles, channel.manualRoute, enabled = !busy) { onRoute(null, it) }
            selectedStream?.let { stream ->
                Text("当前播放源代理", style = MaterialTheme.typography.labelLarge)
                RoutePicker(profiles, stream.manualRoute, enabled = !busy) { onRoute(stream.id, it) }
            }
            val effective = selectedStream?.manualRoute ?: channel.manualRoute
            Text(if (effective?.type == RouteType.PROXY) "首选节点：${routeLabel(effective, profiles)}"
                else if (effective?.type == RouteType.DIRECT) "当前使用直连"
                else "按当前规则与频道地区自动选择节点", style = MaterialTheme.typography.bodySmall)
            TvTextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
            if (advanced) {
                Text("播放源代理优先于频道代理。手动节点为首选，失败后尝试同地区备用节点。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TvOutlinedButton(onClick = { onResetRoutes(); selectedStreamId = null }, enabled = !busy) { Text("恢复自动路由") }
            }
            TvTextButton(onClick = { historyOpen = !historyOpen }) { Text(if (historyOpen) "收起线路记录" else "查看线路记录") }
            if (historyOpen) history()
        }
    }, confirmButton = { TvTextButton(onClick = { onPlay(selectedStream?.id) }, enabled = !busy && channel.streams.isNotEmpty()) { Text("播放") } },
        dismissButton = { TvTextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable internal fun RoutePicker(profiles: List<ProxyProfile>, selected: RouteTarget?, enabled: Boolean = true, onRoute: (RouteTarget?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TvOutlinedButton(onClick = { open = true }, enabled = enabled) { Text(selected?.let { routeLabel(it, profiles) } ?: "跟随规则") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val targets: List<Pair<String, RouteTarget?>> = listOf("跟随规则" to null, "自动" to RouteTarget.AUTO, "直连" to RouteTarget.DIRECT) +
                profiles.filter { it.enabled }.map { it.name to RouteTarget.proxy(it.id) }
            targets.forEach { (label, target) -> DropdownMenuItem(modifier = Modifier.selectionOutline(selected == target), text = { Text(label) }, onClick = { onRoute(target); open = false }) }
        }
    }
}

internal fun routeLabel(route: RouteTarget, profiles: List<ProxyProfile>): String = when (route.type) {
    RouteType.DIRECT -> "直连"
    RouteType.AUTO -> "自动"
    RouteType.PROXY -> profiles.firstOrNull { it.id == route.profile }?.name ?: "节点已移除"
}
internal fun streamName(channel: Channel, id: String, playlists: List<Playlist>): String {
    val stream = channel.streams.firstOrNull { it.id == id } ?: return "线路"
    val name = playlists.firstOrNull { it.id == stream.sourcePlaylistId }?.name ?: "线路"
    val siblings = channel.streams.filter { it.sourcePlaylistId == stream.sourcePlaylistId }
    return if (siblings.size > 1) "$name ${siblings.indexOf(stream) + 1}" else name
}
internal fun date(timestamp: Long?): String = timestamp?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(Date(it)) } ?: "暂无"
private fun redactedUrl(url: String): String = runCatching {
    val uri = java.net.URI(url)
    "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}/…"
}.getOrDefault("已隐藏地址")
internal fun matchLabel(m: com.tvatlas.core.routing.Match): String = listOfNotNull(
    m.channel?.let { "频道：$it" }, m.group?.let { "分组：$it" }, m.domain?.let { "域名：$it" },
    m.domainSuffix?.let { "域名后缀：$it" }, m.channelRegex?.let { "频道正则：$it" },
    m.url?.let { "URL：${redactedUrl(it)}" }, m.urlContains?.let { "URL 关键词（已隐藏）" }, m.playlistId?.let { "播放列表：$it" },
).joinToString(" · ")

@Composable internal fun SubscriptionDialog(busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var scan by remember { mutableStateOf(false) }
    if (scan) {
        PhoneTransferDialog(TransferKind.PROXY, { scan = false }) {
            if (it.name.isNotBlank()) name = it.name else if (name.isBlank()) name = TransferKind.PROXY.defaultName
            url = it.url; clipboardMessage = "手机地址已接收，请确认后下载并导入。"; scan = false
        }
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加 Clash / Mihomo 订阅") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("订阅名称") }, singleLine = true)
            OutlinedTextField(url, { url = it; clipboardMessage = null }, label = { Text("HTTP(S) 订阅地址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            TvTextButton(onClick = {
                val text = clipboard.getText()?.text?.trim().orEmpty()
                if (text.isEmpty()) clipboardMessage = "剪贴板中没有文本，请先复制订阅地址"
                else { url = text; clipboardMessage = null }
            }, enabled = !busy) { Text("粘贴订阅地址") }
            TvOutlinedButton(onClick = { scan = true }, enabled = !busy) { Text("手机扫码输入") }
            clipboardMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("支持 Clash YAML 的 SS、VMess、VLESS、Trojan 等节点。地址及节点密钥仅加密保存在本机。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TvTextButton(onClick = { onSave(name, url) }, enabled = !busy && name.isNotBlank() && httpUri(url.trim()) != null) { Text("下载并导入") } },
        dismissButton = { TvTextButton(onClick = onDismiss) { Text("取消") } })
}


@Composable internal fun DebugLogDialog(lines: List<String>, snapshot: () -> String, onDismiss: () -> Unit,
    onClear: () -> Unit, onExport: () -> Unit,
    categorySnapshot: ((com.tvatlas.player.debug.LogCategory) -> String)? = null,
    categoryLines: ((com.tvatlas.player.debug.LogCategory) -> List<String>)? = null,
    categoryExport: ((com.tvatlas.player.debug.LogCategory) -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    var category by rememberSaveable { mutableStateOf(com.tvatlas.player.debug.LogCategory.ALL) }
    val shown = categoryLines?.invoke(category) ?: lines.filter(category::accepts)
    val selectedSnapshot = { categorySnapshot?.invoke(category) ?: snapshot() }
    var copied by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${category.label}日志（${shown.size} 条）") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                com.tvatlas.player.debug.LogCategory.entries.forEach { item ->
                    TvTextButton(onClick = { category = item; copied = false }, modifier = Modifier.selectionOutline(category == item)) { Text(item.label + "日志") }
                }
            }
            Text("分类查看、复制、扫码或导出；滚动记录最近 500 条，另保留本次启动摘要；不记录完整地址、认证标头或响应正文。失败后导出即可排查。", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TvTextButton(onClick = { shareOpen = true }) { Text("手机扫码获取") }
                TvTextButton(onClick = { clipboard.setText(AnnotatedString(selectedSnapshot())); copied = true }) { Text(if (copied) "已复制" else "复制日志") }
                TvTextButton(onClick = { categoryExport?.invoke(category) ?: onExport() }) { Text("导出 TXT") }
                TvTextButton(onClick = { confirmClear = true }) { Text("清空") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { Text("暂无此类日志") }
                items(shown.asReversed()) { line ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(line, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TvTextButton(onClick = onDismiss) { Text("关闭") } })
    if (shareOpen) LogShareDialog(selectedSnapshot) { shareOpen = false }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空日志？") },
        text = { Text("将删除已保存的诊断记录。") },
        confirmButton = { TvTextButton(onClick = { onClear(); copied = false; confirmClear = false }) { Text("确认清空") } },
        dismissButton = { TvTextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
