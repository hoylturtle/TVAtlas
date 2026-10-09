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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.AnnotatedString
import com.tvatlas.player.PlayerViewModel
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Credentials
import com.tvatlas.player.storage.Library
import com.tvatlas.player.storage.SubscriptionRow
import java.text.DateFormat
import java.util.Date

private val colors = darkColorScheme(primary = Color(0xFFFFB867), secondary = Color(0xFF8FCFC3),
    background = Color(0xFF111419), surface = Color(0xFF191D24), surfaceVariant = Color(0xFF282D36), onPrimary = Color(0xFF482900))

@Composable fun PlayerApp(model: PlayerViewModel) {
    MaterialTheme(colorScheme = colors) {
        val library by model.library.collectAsStateWithLifecycle()
        val status by model.playback.status.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val update by model.update.collectAsStateWithLifecycle()
        val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
        val subscriptions by model.subscriptions.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val config = LocalConfiguration.current
        val tv = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val wide = tv || config.screenWidthDp >= 840
        val landscape = !tv && config.screenWidthDp < 840 && config.orientation == Configuration.ORIENTATION_LANDSCAPE
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var fullscreen by rememberSaveable { mutableStateOf(false) }
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
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
        val immersive = tab == 0 && (fullscreen || (landscape && !landscapeChannels))
        BackHandler(immersive) { fullscreen = false; landscapeChannels = true }
        BackHandler(!immersive && tab != 0) { tab = 0 }
        Scaffold(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!immersive) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TVAtlas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("选频道，即可观看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }, bottomBar = {
                if (!immersive) NavigationBar {
                    listOf("直播", "播放列表", "路由", "设置").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("▶", "▤", "⇄", "⚙")[index]) }, label = { Text(label) })
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> {
                        val video: @Composable (Modifier) -> Unit = { modifier ->
                            VideoPane(model, status, modifier, diagnostics,
                                { if (immersive) { fullscreen = false; landscapeChannels = true } else fullscreen = true },
                                { detailId = status.channelId }, { offset ->
                                    if (library.channels.isNotEmpty()) {
                                        val index = library.channels.indexOfFirst { it.id == status.channelId }.coerceAtLeast(0)
                                        model.play(library.channels[(index + offset + library.channels.size) % library.channels.size])
                                    }
                                })
                        }
                        val select: (Channel) -> Unit = { model.play(it) }
                        when {
                            immersive -> video(Modifier.fillMaxSize())
                            wide -> Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                ChannelList(library, status, tv, Modifier.width(300.dp).fillMaxHeight(), select, { detailId = it.id }, { tab = 1 })
                                video(Modifier.weight(1f).fillMaxHeight())
                            }
                            else -> Column(Modifier.fillMaxSize()) {
                                if (status.channelId != null) video(Modifier.fillMaxWidth().height(240.dp))
                                ChannelList(library, status, tv, Modifier.weight(1f), select, { detailId = it.id }, { tab = 1 })
                            }
                        }
                    }
                    1 -> PlaylistPage(library, busy, { addPlaylist = true }, model::refresh)
                    2 -> RoutingPage(library, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        { exporter.launch("tvatlas-routes.json") }, { addProxy = true }, { editProxyId = it.id },
                        subscriptions, busy, { addSubscription = true }, model::refreshSubscription,
                        { removeSubscription = it }, model::enableNode)
                    3 -> SettingsPage(diagnostics, model::showDiagnostics, update, model::checkUpdate, model::openUpdateDownload)
                }
            }
        }
        if (addPlaylist) PlaylistDialog(busy, { addPlaylist = false }) { name, url -> model.addPlaylist(name, url); addPlaylist = false }
        if (addSubscription) SubscriptionDialog(busy, { addSubscription = false }) { name, url ->
            model.addSubscription(name, url); addSubscription = false
        }
        if (removeSubscription != null) AlertDialog(onDismissRequest = { removeSubscription = null },
            title = { Text("删除订阅？") }, text = { Text("将移除该订阅及其节点。引用这些节点的手动路由需要重新选择。") },
            confirmButton = { TextButton(onClick = { model.deleteSubscription(removeSubscription!!); removeSubscription = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { removeSubscription = null }) { Text("取消") } })
        if (addProxy || editProxyId != null) ProxyDialog(library.profiles.firstOrNull { it.id == editProxyId },
            { addProxy = false; editProxyId = null }) { p, credentials, replace ->
            model.saveProxy(p, credentials, replace); addProxy = false; editProxyId = null
        }
        if (detail != null) ChannelDialog(detail, library.profiles, status, { detailId = null },
            { streamId, target -> model.setRoute(detail.id, streamId, target) },
            { streamId -> model.play(detail, streamId); detailId = null })
    }
}

@Composable private fun ChannelList(library: Library, status: PlaybackStatus, tv: Boolean, modifier: Modifier,
    onPlay: (Channel) -> Unit, onDetails: (Channel) -> Unit, onAdd: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    val firstFocus = remember { FocusRequester() }
    Column(modifier.padding(horizontal = 8.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索频道") })
        Text("${library.channels.size} 个频道", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
        if (library.channels.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("从你的播放列表开始", style = MaterialTheme.typography.titleLarge)
                Text("支持 HTTP(S) M3U / M3U8", Modifier.padding(12.dp))
                Button(onClick = onAdd) { Text("添加播放列表") }
            }
        } else {
            val filtered = library.channels.filter { it.name.contains(query, true) || it.group.contains(query, true) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                filtered.groupBy { it.group.ifBlank { "未分组" } }.forEach { (group, channels) ->
                    item(key = "group:$group") {
                        TextButton(onClick = { collapsed = if (group in collapsed) collapsed - group else collapsed + group }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (group in collapsed) "+" else "−"}  $group", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                            Text("${channels.size}")
                        }
                    }
                    if (group !in collapsed) items(channels, key = { it.id }) { channel ->
                        var focused by remember { mutableStateOf(false) }
                        ListItem(headlineContent = { Text(channel.name, fontWeight = if (status.channelId == channel.id) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text("${channel.streams.size} 条线路") },
                            trailingContent = { if (status.channelId == channel.id) Text("●", color = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = if (status.channelId == channel.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                            modifier = Modifier.then(if (filtered.firstOrNull()?.id == channel.id && tv) Modifier.focusRequester(firstFocus) else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.keyCode in listOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER) &&
                                        event.type == KeyEventType.KeyDown && event.nativeKeyEvent.isLongPress) { onDetails(channel); true } else false
                                }.combinedClickable(onClick = { onPlay(channel) }, onLongClick = { onDetails(channel) }))
                    }
                }
            }
        }
    }
    LaunchedEffect(tv, library.channels.isNotEmpty()) { if (tv && library.channels.isNotEmpty()) runCatching { firstFocus.requestFocus() } }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoPane(model: PlayerViewModel, status: PlaybackStatus, modifier: Modifier, diagnostics: Boolean,
    onFullscreen: () -> Unit, onDetails: () -> Unit, onChannel: (Int) -> Unit) {
    val player by model.playback.player.collectAsStateWithLifecycle()
    Column(modifier.background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (player == null) Text(status.message, Modifier.padding(24.dp), color = Color.White)
            else AndroidView(factory = { PlayerView(it).apply { useController = true; keepScreenOn = true } }, onReset = null, update = { it.player = player },
                onRelease = { it.player = null }, modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> { onChannel(-1); true }
                        Key.DirectionDown -> { onChannel(1); true }
                        Key.DirectionCenter, Key.Enter -> if (event.nativeKeyEvent.isLongPress) { onDetails(); true } else false
                        else -> false
                    }
                })
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status.message, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            TextButton(onClick = onDetails, enabled = status.channelId != null) { Text("线路") }
            TextButton(onClick = onFullscreen) { Text("全屏 / 频道") }
            TextButton(onClick = { model.stopPlayback() }, enabled = player != null) { Text("停止") }
        }
        if (diagnostics && status.attempt != null) Text("${redactedUrl(status.attempt.streamUrl)}\n${routeLabel(status.attempt.target)} · ${status.attempt.matchedRule}" +
            (status.error?.let { "\n$it" } ?: ""), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PlaylistPage(library: Library, busy: Boolean, onAdd: () -> Unit, onRefresh: (Playlist) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("播放列表", style = MaterialTheme.typography.headlineMedium)
            Text("同名频道自动合并；更新保留路由和播放记忆。", Modifier.padding(vertical = 12.dp))
            Button(onClick = onAdd, enabled = !busy) { Text("添加播放列表") }
        }
        items(library.playlists, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge)
                Text(redactedUrl(p.url), style = MaterialTheme.typography.bodySmall)
                Text("最近更新：${date(p.lastUpdatedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onRefresh(p) }, enabled = !busy) { Text("刷新") }
            } }
        }
    }
}

@Composable private fun RoutingPage(library: Library, onImport: () -> Unit, onExport: () -> Unit, onAdd: () -> Unit, onEdit: (ProxyProfile) -> Unit,
    subscriptions: List<SubscriptionRow>, busy: Boolean, onSubscribe: () -> Unit, onRefresh: (String) -> Unit,
    onDelete: (String) -> Unit, onEnable: (ProxyProfile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("智能路由", style = MaterialTheme.typography.headlineMedium)
            Text("按频道或线路选择直连、代理或自动路由。", Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport) { Text("导入 JSON") }
                OutlinedButton(onClick = onExport) { Text("导出规则") }
            }
            Text("${library.rules.name} · ${library.rules.rules.size} 条规则", Modifier.padding(vertical = 12.dp))
            Text("默认：${routeLabel(library.rules.defaultRoute)}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onSubscribe, enabled = !busy, modifier = Modifier.padding(top = 16.dp)) { Text("添加 Clash / Mihomo 订阅") }
            Text("粘贴订阅地址即可导入节点，在频道或线路菜单中选择使用。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAdd) { Text("手动添加 HTTP / SOCKS5") }
        }
        items(subscriptions, key = { "subscription:${it.id}" }) { sub ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(sub.name, style = MaterialTheme.typography.titleLarge)
                Text("${sub.nodeCount} 个节点 · 更新于 ${date(sub.lastUpdatedAt)}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onRefresh(sub.id) }, enabled = !busy) { Text("更新订阅") }
                    TextButton(onClick = { onDelete(sub.id) }, enabled = !busy) { Text("删除") }
                }
            } }
        }
        items(library.profiles, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text("${p.name} · ${p.id}") },
                supportingContent = { Text("${if (p.type == ProxyType.MIHOMO) "订阅节点" else p.type.name} · ${p.host}:${p.port} · ${if (p.enabled) "启用" else "停用"}") },
                trailingContent = { if (p.type == ProxyType.MIHOMO) Switch(checked = p.enabled, onCheckedChange = { onEnable(p) }, enabled = !busy)
                    else TextButton(onClick = { onEdit(p) }) { Text("编辑") } }) }
        }
        items(library.rules.rules, key = { "rule:${it.id}" }) { rule ->
            ListItem(headlineContent = { Text(rule.id) }, supportingContent = { Text(matchLabel(rule.match), style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Text(routeLabel(rule.route)) })
        }
    }
}

@Composable internal fun SettingsPage(diagnostics: Boolean, onDiagnostics: (Boolean) -> Unit,
    update: UpdateState, onCheckUpdate: () -> Unit, onDownload: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var linkCopied by remember(update.release?.downloadUrl) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        ListItem(headlineContent = { Text("播放诊断") }, supportingContent = { Text("显示脱敏地址、路由、命中规则和错误类型") },
            trailingContent = { Switch(checked = diagnostics, onCheckedChange = onDiagnostics) })
        Text("应用更新", style = MaterialTheme.typography.titleLarge)
        Text("当前版本：${BuildConfig.VERSION_NAME}")
        Button(onClick = onCheckUpdate, enabled = !update.checking) { Text(if (update.checking) "正在检查更新…" else "检查更新") }
        update.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        update.release?.let { release ->
            if (release.versionCode > BuildConfig.VERSION_CODE) {
                Text("发现新版本：${release.versionName}", style = MaterialTheme.typography.titleMedium)
                if (release.notes.isNotBlank()) Text(release.notes)
                if (release.minSdk > android.os.Build.VERSION.SDK_INT) Text("新版要求 Android API ${release.minSdk} 或以上，当前设备暂不能升级")
                else {
                    Button(onClick = onDownload) { Text("打开下载页面") }
                    Text("在 GitHub 登录后下载 ZIP，解压安装其中的 APK。调试签名可能变化；升级前记下播放列表地址、导出路由规则，卸载会清除旧数据。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(release.downloadUrl)); linkCopied = true }) {
                    Text(if (linkCopied) "下载链接已复制" else "复制下载链接")
                }
            } else Text("已是最新版本")
        }
        Text("遥控器操作", style = MaterialTheme.typography.titleLarge)
        Text("频道列表：方向键选择，OK 播放，长按 OK 查看线路。\n播放器：上下换台，长按 OK 查看线路；返回退出全屏。")
        Text("配置与隐私", style = MaterialTheme.typography.titleLarge)
        Text("代理凭证使用 Android Keystore 加密保存在本机。导出的规则不包含用户名或密码。播放历史仅保存在本机。")
        Text("TVAtlas Player · 开发版 ${BuildConfig.VERSION_NAME} · Mihomo v1.19.32", color = MaterialTheme.colorScheme.secondary)
        Text("Mihomo © MetaCubeX / Clash contributors · GPL-3.0。许可证与对应源码随安装包提供；内核按许可证提供，无担保。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PlaylistDialog(busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加播放列表") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("HTTP(S) M3U 地址") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = url.isNotEmpty() && httpUri(url.trim()) == null)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, url) }, enabled = !busy && name.isNotBlank() && httpUri(url.trim()) != null) { Text("加载并保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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
    }, confirmButton = { TextButton(onClick = {
        val profile = ProxyProfile(id.trim(), name.trim(), type, host.trim(), port.toIntOrNull() ?: 0, enabled)
        val valid = runCatching { RuleCodec.validateProxy(profile); require(clearCredentials || (username.isBlank() == password.isBlank())) { "用户名和密码需同时填写" } }
        if (valid.isSuccess) onSave(profile, if (clearCredentials || username.isBlank()) null else Credentials(username, password),
            existing == null || clearCredentials || username.isNotBlank()) else error = valid.exceptionOrNull()?.message
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun ChannelDialog(channel: Channel, profiles: List<ProxyProfile>, status: PlaybackStatus, onDismiss: () -> Unit,
    onRoute: (String?, RouteTarget?) -> Unit, onPlay: (String?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(channel.name) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("频道路由：${channel.manualRoute?.let(::routeLabel) ?: "跟随规则"}")
            RoutePicker(profiles, channel.manualRoute) { onRoute(null, it) }
            if (status.channelId == channel.id) {
                Text("最近成功：${date(status.successAt)}", style = MaterialTheme.typography.bodySmall)
                status.attempt?.let { Text("${routeLabel(it.target)} · ${it.matchedRule}", style = MaterialTheme.typography.bodySmall) }
            }
            channel.streams.forEachIndexed { index, stream ->
                HorizontalDivider()
                Text("线路 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                Text(redactedUrl(stream.url), style = MaterialTheme.typography.bodySmall)
                Text("成功：${date(stream.lastSuccessAt)} · 失败 ${stream.failureCount} 次", style = MaterialTheme.typography.bodySmall)
                RoutePicker(profiles, stream.manualRoute) { onRoute(stream.id, it) }
                OutlinedButton(onClick = { onPlay(stream.id) }) { Text("播放此线路") }
            }
        }
    }, confirmButton = { TextButton(onClick = { onPlay(null) }) { Text("自动选线播放") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable private fun RoutePicker(profiles: List<ProxyProfile>, selected: RouteTarget?, onRoute: (RouteTarget?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(selected?.let(::routeLabel) ?: "跟随规则") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val targets: List<Pair<String, RouteTarget?>> = listOf("恢复规则" to null, "AUTO" to RouteTarget.AUTO, "DIRECT" to RouteTarget.DIRECT) +
                profiles.filter { it.enabled }.map { it.name to RouteTarget.proxy(it.id) }
            targets.forEach { (label, target) -> DropdownMenuItem(text = { Text(label) }, onClick = { onRoute(target); open = false }) }
        }
    }
}

private fun routeLabel(route: RouteTarget): String = when (route.type) {
    RouteType.DIRECT -> "DIRECT"
    RouteType.AUTO -> "AUTO"
    RouteType.PROXY -> "PROXY · ${route.profile}"
}
private fun date(timestamp: Long?): String = timestamp?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "暂无"
private fun redactedUrl(url: String): String = runCatching {
    val uri = java.net.URI(url)
    "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}/…"
}.getOrDefault("已隐藏地址")
private fun matchLabel(m: com.tvatlas.core.routing.Match): String = listOfNotNull(
    m.channel?.let { "频道：$it" }, m.group?.let { "分组：$it" }, m.domain?.let { "域名：$it" },
    m.domainSuffix?.let { "域名后缀：$it" }, m.channelRegex?.let { "频道正则：$it" },
    m.url?.let { "URL：${redactedUrl(it)}" }, m.urlContains?.let { "URL 关键词（已隐藏）" }, m.playlistId?.let { "播放列表：$it" },
).joinToString(" · ")

@Composable internal fun SubscriptionDialog(busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加 Clash / Mihomo 订阅") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("订阅名称") }, singleLine = true)
            OutlinedTextField(url, { url = it; clipboardMessage = null }, label = { Text("HTTP(S) 订阅地址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            TextButton(onClick = {
                val text = clipboard.getText()?.text?.trim().orEmpty()
                if (text.isEmpty()) clipboardMessage = "剪贴板中没有文本，请先复制订阅地址"
                else { url = text; clipboardMessage = null }
            }, enabled = !busy) { Text("粘贴订阅地址") }
            clipboardMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("支持 Clash YAML 的 SS、VMess、VLESS、Trojan 等节点。地址及节点密钥仅加密保存在本机。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, url) }, enabled = !busy && name.isNotBlank() && httpUri(url.trim()) != null) { Text("下载并导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
