@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.tvatlas.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

internal val colors = darkColorScheme(primary = Color(0xFFB5CCFF), secondary = Color(0xFFB8C3D8),
    background = Color(0xFF0D0F13), surface = Color(0xFF171A21), surfaceVariant = Color(0xFF292F3A), onPrimary = Color(0xFF162A4B))

@Composable fun PlayerApp(model: PlayerViewModel) {
    MaterialTheme(colorScheme = colors) {
        val library by model.library.collectAsStateWithLifecycle()
        val status by model.playback.status.collectAsStateWithLifecycle()
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
        val ruleFiles by model.ruleFiles.collectAsStateWithLifecycle()
        val subscriptions by model.subscriptions.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val config = LocalConfiguration.current
        val tv = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val wide = tv || (config.screenWidthDp >= 840 && config.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val landscape = !tv && config.screenWidthDp < 840 && config.orientation == Configuration.ORIENTATION_LANDSCAPE
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var logsOpen by rememberSaveable { mutableStateOf(false) }
        val logLines by model.debugLog.lines.collectAsStateWithLifecycle()
        var fullscreen by rememberSaveable { mutableStateOf(false) }
        var channelPanelOpen by rememberSaveable { mutableStateOf(true) }
        var landscapeChannels by rememberSaveable { mutableStateOf(false) }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        var addPlaylist by rememberSaveable { mutableStateOf(false) }
        var addProxy by rememberSaveable { mutableStateOf(false) }
        var addSubscription by rememberSaveable { mutableStateOf(false) }
        var removeSubscription by rememberSaveable { mutableStateOf<String?>(null) }
        var editProxyId by rememberSaveable { mutableStateOf<String?>(null) }
        val detail = library.channels.firstOrNull { it.id == detailId }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importRules) }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::exportRules) }
        val logExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(model::exportLogs) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
        val immersive = tab == 0 && (fullscreen || (landscape && !landscapeChannels) || (wide && !channelPanelOpen))
        val theater = tab == 0 && wide
        BackHandler(immersive) { fullscreen = false; landscapeChannels = true; channelPanelOpen = true }
        BackHandler(theater && channelPanelOpen && status.channelId != null) { channelPanelOpen = false }
        BackHandler(!immersive && tab != 0) { tab = 0 }
        PlaybackOrientation(immersive, tv)
        PlaybackSystemBars(immersive)
        Scaffold(modifier = Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
            contentWindowInsets = if (immersive) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!tv && !immersive && !theater && tab == 0) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TVAtlas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("选频道，即可观看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }, bottomBar = {
                if (!tv && !immersive) NavigationBar {
                    listOf("直播", "频道源", "代理", "设置").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("▶", "▤", "⇄", "⚙")[index]) }, label = { Text(label) })
                    }
                }
            }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (tv && !immersive) TvNavigation(tab, { tab = it; channelPanelOpen = true }, Modifier.fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    0 -> {
                        val video: @Composable (Modifier) -> Unit = { modifier ->
                            VideoPane(model, status, library, modifier, diagnostics, tv, tv && !channelPanelOpen, true, immersive, !tv && !wide && !immersive,
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
                                })
                        }
                        val select: (Channel) -> Unit = {
                            if (status.channelId != it.id || model.playback.player.value == null) model.play(it)
                            if (wide) channelPanelOpen = false
                        }
                        when {
                            immersive -> video(Modifier.fillMaxSize())
                            wide -> TvBrowseLayout(tv, library.channels.firstOrNull { it.id == status.channelId }?.name,
                                channels = { ChannelList(library, status, tv, Modifier.fillMaxSize(), select, { detailId = it.id }, { tab = 1 }) },
                                picture = { video(Modifier.fillMaxSize()) })
                            else -> MobileLiveLayout(status.channelId != null, Modifier.fillMaxSize(), picture = video,
                                channels = { ChannelList(library, status, tv, it, select, { detailId = it.id }, { tab = 1 }) })
                        }
                    }
                    1 -> PlaylistPage(library, busy, { addPlaylist = true }, model::refresh, model::refreshBuiltIn, playlistFeedback)
                    2 -> RoutingPage(library, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        { exporter.launch("tvatlas-routes.json") }, { addProxy = true }, { editProxyId = it.id },
                        subscriptions, busy, { addSubscription = true }, model::refreshSubscription,
                        { removeSubscription = it }, model::enableNode, subscriptionRoute, model::setSubscriptionRoute,
                        subscriptionFeedback, updatingSubscription, coreInfo, model::refreshCoreInfo, { tab = 3; model.checkUpdate() }, nodeHealth, nodeTesting, model::testNodes, ruleFiles, model::selectRuleFile)
                    3 -> SettingsPage(diagnostics, model::showDiagnostics, update, model::checkUpdate, model::openUpdateDownload,
                        { logsOpen = true }, model::openRepository, library.profiles, updateRoute, model::setUpdateRoute, model::installUpdate, model::cancelUpdate)
                }
                }
            }
        }
        if (logsOpen) DebugLogDialog(logLines, model.debugLog::snapshot, { logsOpen = false }, model::clearLogs, { logExporter.launch("tvatlas-debug-v${BuildConfig.VERSION_NAME}.txt") })
        if (addPlaylist) PlaylistDialog(busy, { addPlaylist = false }) { name, url -> model.addPlaylist(name, url); addPlaylist = false }
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
            { streamId -> model.play(detail, streamId); detailId = null }, { model.resetChannelRoutes(detail.id) }, busy)
    }
}

@Composable internal fun ChannelList(library: Library, status: PlaybackStatus, tv: Boolean, modifier: Modifier,
    onPlay: (Channel) -> Unit, onDetails: (Channel) -> Unit, onAdd: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val firstFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    Column(modifier.padding(horizontal = 8.dp)) {
        if (tv) Text("确认展开分组 · 长按频道设置线路", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索频道") })
        Text("${library.channels.size} 个频道", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
        if (library.channels.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("从你的播放列表开始", style = MaterialTheme.typography.titleLarge)
                Text("支持 HTTP(S) M3U / M3U8", Modifier.padding(12.dp))
                TvButton(onClick = onAdd) { Text("添加播放列表") }
            }
        } else {
            val filtered = library.channels.filter { it.name.contains(query, true) || it.group.contains(query, true) }.hongKongFirst()
            LazyColumn(Modifier.fillMaxSize().testTag("channel-list"), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                filtered.groupBy { it.group.ifBlank { "未分组" } }.forEach { (group, channels) ->
                    item(key = "group:$group") {
                        TvTextButton(onClick = { expanded = if (group in expanded) expanded - group else expanded + group },
                            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (group in expanded || query.isNotBlank()) "已展开" else "已收起" }
                                .then(if (tv) Modifier.tvFocusOutline() else Modifier)
                                .then(if (tv) Modifier.focusProperties { canFocus = true } else Modifier)
                                .then(if (tv && group == filtered.firstOrNull()?.group?.ifBlank { "未分组" }) Modifier.focusRequester(firstFocus) else Modifier)) {
                            Text("${if (group in expanded || query.isNotBlank()) "⌄" else "›"}  $group", Modifier.weight(1f), color = if (tv) LocalContentColor.current else MaterialTheme.colorScheme.secondary)
                            Text("${channels.size}")
                        }
                    }
                    if (group in expanded || query.isNotBlank()) items(channels, key = { it.id }) { channel ->
                        var focused by remember { mutableStateOf(false) }
                        ListItem(headlineContent = { Text(channel.name, fontWeight = if (status.channelId == channel.id) FontWeight.Bold else FontWeight.Normal) },
                            trailingContent = { if (status.channelId == channel.id) Text("●", color = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = if (focused) MaterialTheme.colorScheme.primary else if (status.channelId == channel.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, headlineColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.onFocusChanged { focused = it.isFocused }
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
    LaunchedEffect(tv, library.channels.isNotEmpty()) {
        if (tv && library.channels.isNotEmpty()) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { } // LazyColumn group buttons must be laid out before focus is requested.
            runCatching { firstFocus.requestFocus() }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoPane(model: PlayerViewModel, status: PlaybackStatus, library: Library, modifier: Modifier, diagnostics: Boolean, tv: Boolean, pictureFocused: Boolean, fillPicture: Boolean, immersive: Boolean, compact: Boolean,
    onFullscreen: () -> Unit, onShowChannels: () -> Unit, onDetails: () -> Unit, onChannel: (Int) -> Unit) {
    val player by model.playback.player.collectAsStateWithLifecycle()
    val replay = { library.channels.firstOrNull { it.id == status.channelId }?.let { model.play(it, status.selectedStreamId) }; Unit }
    val picture: @Composable () -> Unit = {
            if (player == null) Text(if (compact && status.error != null && !status.loading) "播放未成功" else status.message, Modifier.padding(16.dp), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            else AndroidView(factory = { PlayerView(it).apply { resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT; useController = !tv && !immersive; keepScreenOn = true; isFocusable = !immersive && (!tv || pictureFocused); isFocusableInTouchMode = !immersive && (!tv || pictureFocused); if (pictureFocused && !immersive) post { requestFocus() } } }, onReset = null, update = { it.player = player; it.useController = !tv && !immersive; it.isFocusable = !immersive && (!tv || pictureFocused); it.isFocusableInTouchMode = !immersive && (!tv || pictureFocused); if (pictureFocused && !immersive && !it.hasFocus()) it.post { it.requestFocus() } },
                onRelease = { it.player = null }, modifier = Modifier.fillMaxSize().focusProperties { canFocus = !immersive && (!tv || pictureFocused) }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> { onChannel(-1); true }
                        Key.DirectionDown -> { onChannel(1); true }
                        Key.DirectionLeft -> if (tv) { onShowChannels(); true } else false
                        Key.DirectionCenter, Key.Enter -> if (event.nativeKeyEvent.isLongPress) { onDetails(); true } else if (tv) { onShowChannels(); true } else false
                        else -> false
                    }
                })
        }
    val controls: @Composable () -> Unit = {
        if (tv && pictureFocused && !immersive) Text("画面 ↑↓ 换台 · 确认 显示操作 · 操作栏 ↑ 收起 · 长按确认 线路 · 返回 频道菜单", Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        val displayedAttempt = status.attempt ?: status.lastAttempt
        VideoControls(if (compact && status.error != null && !status.loading) "播放未成功" else status.message, displayedAttempt?.let { attempt ->
            library.channels.firstOrNull { it.id == status.channelId }?.let { streamName(it, attempt.streamId, library.playlists) }
        } ?: "线路", displayedAttempt?.let { routeLabel(it.target, library.profiles) }.orEmpty(),
            status.channelId != null, player != null || status.loading, onDetails, onFullscreen, model::stopPlayback,
            canReplay = player == null && !status.loading && status.channelId != null, onReplay = replay,
            fullscreenLabel = if (immersive) "退出全屏" else "全屏 / 频道", immersive = immersive,
            fullscreenRoute = displayedAttempt?.let { attempt -> when (attempt.target.type) {
                RouteType.DIRECT -> "直连"
                RouteType.PROXY -> library.profiles.firstOrNull { it.id == attempt.target.profile }?.let { playbackRegion(it.name)?.label }?.let { "$it代理" } ?: "代理"
                RouteType.AUTO -> "自动代理"
            } }.orEmpty())

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
        onChannel, onDetails, picture, controls)
    else PlayerPaneLayout(fillPicture, modifier, picture, controls)
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
    immersive: Boolean = false, fullscreenRoute: String = proxy) {
    if (immersive) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvTextButton(onClick = onDetails, enabled = canDetails, modifier = Modifier.weight(1f)) {
                Text(listOf(line, fullscreenRoute).filter { it.isNotBlank() }.joinToString(" | "),
                    Modifier.fillMaxWidth(), color = Color.White, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvTextButton(onClick = onFullscreen) { Text(fullscreenLabel, color = Color.White, maxLines = 1) }
            TvTextButton(onClick = if (canReplay) onReplay else onStop, enabled = canStop || canReplay) {
                Text(if (canReplay) "重新播放" else "停止", color = Color.White, maxLines = 1)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (proxy.isNotBlank()) Text(proxy, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable internal fun PlaylistDialog(busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var scan by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf(false) }
    if (scan) {
        PhoneTransferDialog(TransferKind.PLAYLIST, { scan = false }) {
            if (it.name.isNotBlank()) name = it.name else if (name.isBlank()) name = TransferKind.PLAYLIST.defaultName
            url = it.url; received = true; scan = false
        }
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加播放列表") }, text = {
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
    onResetRoutes: () -> Unit = { onRoute(null, null); channel.streams.forEach { onRoute(it.id, null) } }, busy: Boolean = false) {
    var selectedStreamId by remember(channel.id) {
        mutableStateOf((status.attempt ?: status.lastAttempt)?.takeIf { status.channelId == channel.id }?.streamId)
    }
    val selectedStream = channel.streams.firstOrNull { it.id == selectedStreamId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(channel.name) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("播放源", style = MaterialTheme.typography.labelLarge)
            var lineMenu by remember { mutableStateOf(false) }
            Box {
                TvOutlinedButton(onClick = { lineMenu = true }, enabled = channel.streams.isNotEmpty()) {
                    Text(selectedStream?.let { streamName(channel, it.id, playlists) } ?: "自动选线")
                }
                DropdownMenu(expanded = lineMenu, onDismissRequest = { lineMenu = false }) {
                    DropdownMenuItem(modifier = Modifier.tvFocusOutline(), text = { Text("自动选线") }, onClick = { selectedStreamId = null; lineMenu = false })
                    channel.streams.forEach { stream ->
                        DropdownMenuItem(modifier = Modifier.tvFocusOutline(), text = { Text(streamName(channel, stream.id, playlists)) }, onClick = { selectedStreamId = stream.id; lineMenu = false })
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
            Text("播放源代理优先于频道代理。手动节点为首选，失败后尝试同地区备用节点。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvOutlinedButton(onClick = { onResetRoutes(); selectedStreamId = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复自动路由") }
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
            targets.forEach { (label, target) -> DropdownMenuItem(modifier = Modifier.tvFocusOutline(), text = { Text(label) }, onClick = { onRoute(target); open = false }) }
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
    onClear: () -> Unit, onExport: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("调试日志（${lines.size} 条）") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近 500 条，重启后保留；不记录完整地址、认证标头或响应正文。失败后导出即可排查。", style = MaterialTheme.typography.bodySmall)
            Row {
                TvTextButton(onClick = { clipboard.setText(AnnotatedString(snapshot())); copied = true }) { Text(if (copied) "已复制" else "复制日志") }
                TvTextButton(onClick = onExport) { Text("导出 TXT") }
                TvTextButton(onClick = { confirmClear = true }) { Text("清空") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.isEmpty()) item { Text("暂无日志，请重试播放后查看") }
                items(lines.asReversed()) { line ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(line, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TvTextButton(onClick = onDismiss) { Text("关闭") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空日志？") },
        text = { Text("将删除已保存的诊断记录。") },
        confirmButton = { TvTextButton(onClick = { onClear(); copied = false; confirmClear = false }) { Text("确认清空") } },
        dismissButton = { TvTextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
