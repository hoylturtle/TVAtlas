@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tvatlas.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tvatlas.core.cloud.*
import com.tvatlas.player.cloud.CloudLibraryState
import com.tvatlas.player.transfer.TransferKind

@Composable internal fun CloudLibraryPage(state: CloudLibraryState,
    onImport: (String, String, String, () -> Unit) -> Unit, onOpen: (CloudShare) -> Unit,
    onEnter: (CloudFile) -> Unit, onBack: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onRemove: (CloudShare) -> Unit, onWebsite: (CloudShare) -> Unit, importRequest: Int = 0,
    onDirectVideo: (String, String) -> Unit = { _, _ -> }, onManageSources: () -> Unit = {}, onCatalog: () -> Unit = {}) {
    var videoPhoneInput by remember { mutableStateOf(false) }
    var directVideo by remember { mutableStateOf(false) }
    var videoUrl by remember { mutableStateOf("") }
    var videoTitle by remember { mutableStateOf("") }
    var videoError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(importRequest) { if (importRequest > 0) importing = true }
    var phoneInput by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<CloudShare?>(null) }
    var showAuthorization by remember { mutableStateOf(false) }
    var query by remember(state.selected?.key, state.folders) { mutableStateOf("") }
    var videosOnly by remember { mutableStateOf(false) }
    val directoryScroll = remember { LinkedHashMap<String, LazyListState>() }
    var selectedFile by remember { mutableStateOf<CloudFile?>(null) }
    val current = state.selected
    var pathOpen by remember(current?.key) { mutableStateOf(false) }
    BackHandler(state.selected != null && !importing && !phoneInput && deleting == null && selectedFile == null && !showAuthorization && !directVideo && !videoPhoneInput && !pathOpen, onBack)
    BackHandler(state.selected == null && !importing && !phoneInput && deleting == null && selectedFile == null && !showAuthorization && !directVideo && !videoPhoneInput && !pathOpen, onCatalog)
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        val split = maxWidth >= 700.dp && maxWidth > maxHeight
        val sidebarWidth = (maxWidth * .29f).coerceIn(220.dp, 300.dp)
        val navigation: @Composable ColumnScope.() -> Unit = {
            AtlasHeader("影视 · 网盘分享", "夸克 / 百度网盘 / 115")
            if (state.shares.isNotEmpty()) {
                Text("已导入分享", style = MaterialTheme.typography.labelLarge)
                state.shares.forEach { share ->
                    TvTextButton(onClick = { onOpen(share) }, modifier = Modifier.fillMaxWidth()
                        .selectionOutline(share.key == current?.key)) {
                        Text(share.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (current != null) {
                Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.folders.joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TvTextButton(onClick = { pathOpen = true }) { Text("查看完整路径") }
                AtlasActionButton(onClick = onBack) { Text(if (state.folders.size > 1) "上一级" else "返回分享") }
                TvTextButton(onClick = onRefresh, enabled = !state.loading) { Text("重新读取") }
                TvTextButton(onClick = { onWebsite(current) }) { Text("打开官网") }
                TvTextButton(onClick = onManageSources) { Text("管理来源") }
            }
        }
        if (current == null) {
            val overview: @Composable ColumnScope.() -> Unit = {
                AtlasHeader("我的网盘", "已保存的分享，打开后浏览文件夹") {
                    TvTextButton(onClick = onCatalog) { Text("影视片库") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AtlasActionButton(onClick = onManageSources) { Text("添加 / 管理分享") }
                    TvTextButton(onClick = { directVideo = true }) { Text("视频直链") }
                    TvTextButton(onClick = { showAuthorization = true }) { Text("账号授权") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.shares.isEmpty()) AtlasPanel(Modifier.fillMaxWidth()) {
                    Text("还没有保存的网盘分享", style = MaterialTheme.typography.titleMedium)
                    Text("到频道源导入夸克、百度或 115 分享链接。保存后可从这里继续浏览文件夹。", style = MaterialTheme.typography.bodyMedium)
                    AtlasActionButton(onClick = onManageSources) { Text("导入分享链接") }
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(240.dp), modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    gridItems(state.shares, key = { it.key }) { share ->
                        AtlasCard(onClick = { onOpen(share) }, modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(share.provider.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                                Text(share.name, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (share.provider == CloudProvider.BAIDU) "查看接入状态 ›" else "打开文件夹 ›", style = MaterialTheme.typography.bodySmall)
                                TvTextButton(onClick = { deleting = share }) { Text("移除记录") }
                            }
                        }
                    }
                }
            }
            // The overview uses the whole screen; split only after a folder is opened.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), content = overview)
        } else {
            val browser: @Composable ColumnScope.() -> Unit = {
                Text(state.folders.lastOrNull()?.name ?: "分享根目录", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val visibleFiles = remember(state.files, query, videosOnly) {
                    state.files.filter { (it.directory || !videosOnly || it.video) && it.name.contains(query.trim(), ignoreCase = true) }
                }
                OutlinedTextField(query, { query = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索当前已加载文件") }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) TvTextButton(onClick = { query = "" }) { Text("清除") } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${visibleFiles.size} 项 / 已加载 ${state.files.size} 项", style = MaterialTheme.typography.bodySmall)
                    TvTextButton(onClick = { videosOnly = !videosOnly }) { Text(if (videosOnly) "显示全部文件" else "只看视频和文件夹") }
                }
                val scrollKey = current.key + ":" + state.folders.joinToString("/") { it.id }
                val scroll = directoryScroll.getOrPut(scrollKey) { LazyListState() }
                while (directoryScroll.size > 12) directoryScroll.remove(directoryScroll.keys.first())
                LazyColumn(Modifier.weight(1f).testTag("cloud-files"), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.loading && state.error == null && visibleFiles.isEmpty()) item {
                        Text(if (state.files.isEmpty()) "这个文件夹暂未返回文件" else "已加载文件中没有匹配项；可清除筛选或加载更多")
                    }
                    items(visibleFiles, key = { "${it.directory}:${it.id}" }) { file ->
                        TvTextButton(onClick = { if (file.directory) onEnter(file) else selectedFile = file }, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (file.directory) "文件夹" else if (file.video) "视频 · 原生播放待接入" else "文件",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (file.directory) "›" else "详情")
                        }
                    }
                    if (state.nextOffset != null) item { AtlasActionButton(onClick = onMore, enabled = !state.loading) { Text(if (state.error != null) "重试加载更多" else "加载更多") } }
                }

            }
            if (split) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(sidebarWidth).fillMaxHeight()
                    .testTag("cloud-navigation").focusGroup().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = navigation)
                Column(Modifier.weight(1f).fillMaxHeight().testTag("cloud-browser").focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = browser)
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvTextButton(onClick = onBack) { Text(if (state.folders.size > 1) "上一级" else "返回分享") }
                    TvTextButton(onClick = { pathOpen = true }) { Text("路径／操作") }
                }
                browser()
            }
        }
    }
    if (pathOpen && current != null) AlertDialog(onDismissRequest = { pathOpen = false },
        title = { Text("当前路径") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.folders.joinToString(" / ") { it.name })
                TvTextButton(onClick = { pathOpen = false; onRefresh() }, enabled = !state.loading) { Text("重新读取") }
                TvTextButton(onClick = { pathOpen = false; onWebsite(current) }) { Text("打开官网") }
                TvTextButton(onClick = { pathOpen = false; onManageSources() }) { Text("管理来源") }
            }
        }, confirmButton = { TvTextButton(onClick = { pathOpen = false }) { Text("关闭") } })
    if (directVideo) AlertDialog(onDismissRequest = { directVideo = false; videoUrl = ""; videoTitle = ""; videoError = null },
        title = { Text("视频直链点播") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("用于已取得的视频直链，不会把网盘分享网页当作视频。本阶段使用直连，支持拖动、音轨／字幕和本机续播。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(videoTitle, { videoTitle = it.take(80) }, label = { Text("视频名称（可选）") }, singleLine = true)
                OutlinedTextField(videoUrl, { videoUrl = it.take(8192) }, label = { Text("HTTP(S) 视频直链") }, maxLines = 3)
                Text("地址只保留在本次播放内存中，不加入直播频道、不保存完整地址或登录信息。再次输入相同地址可恢复本机进度。", style = MaterialTheme.typography.bodySmall)
                AtlasActionButton(onClick = { directVideo = false; videoPhoneInput = true; videoUrl = "" }) { Text("手机扫码输入视频直链") }
                videoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TvButton(onClick = {
            try {
                val item = com.tvatlas.core.vod.VodItem.direct(videoUrl, videoTitle)
                onDirectVideo(item.url, item.title)
                directVideo = false; videoUrl = ""; videoTitle = ""; videoError = null
            } catch (error: IllegalArgumentException) { videoError = error.message }
        }, enabled = videoUrl.isNotBlank()) { Text("打开视频") } },
        dismissButton = { TvTextButton(onClick = { directVideo = false; videoUrl = ""; videoTitle = ""; videoError = null }) { Text("取消") } })
    if (videoPhoneInput) PhoneTransferDialog(TransferKind.VIDEO, { videoPhoneInput = false }) { input ->
        videoPhoneInput = false; videoUrl = input.url; videoTitle = input.name; directVideo = true
    }
    if (showAuthorization) AlertDialog(onDismissRequest = { showAuthorization = false },
        title = { Text("官方账号授权 · 接入状态") }, text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("目前未登录任何网盘账号。打开官网登录不会自动授权 TVAtlas。")
                Text("115：官方有开发者开放平台；本项目尚未配置经批准的应用身份，因此暂不能发起官方扫码授权。")
                Text("百度网盘：官方提供 OAuth 授权；本项目尚未配置应用登记与回调，暂不能发起授权。分享取流能力还需单独核实。")
                Text("夸克：官方提供网盘相关开放能力；适用于本播放器的账号授权及分享取流接入方式尚在核实，暂不开放登录。")
                Text("后续优先接入官方扫码／授权。不会借用其他应用身份、要求粘贴 Cookie，或在未确认时转存分享。")
            }
        }, confirmButton = { TvTextButton(onClick = { showAuthorization = false }) { Text("知道了") } })
    if (importing) AlertDialog(onDismissRequest = { importing = false; text = ""; code = "" }, title = { Text("导入网盘分享") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AtlasActionButton(onClick = { importing = false; phoneInput = true }) { Text("手机扫码导入") }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("名称（可选）") }, singleLine = true)
            OutlinedTextField(text, { text = it.take(8192) }, label = { Text("完整分享文字或链接") }, maxLines = 5)
            OutlinedTextField(code, { code = it.take(4) }, label = { Text("提取码（可选，用于修正识别结果）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("分享链接和提取码加密保存在本机，不写入直播订阅和调试日志。", style = MaterialTheme.typography.bodySmall)
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TvButton(onClick = { onImport(text, code, name) { importing = false; text = ""; code = ""; name = "" } }, enabled = text.isNotBlank()) { Text("识别并保存") } },
        dismissButton = { TvTextButton(onClick = { importing = false; text = ""; code = "" }) { Text("取消") } })
    if (phoneInput) PhoneTransferDialog(TransferKind.CLOUD, { phoneInput = false }) { input -> phoneInput = false; text = input.url; name = input.name; importing = true }
    deleting?.let { share -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除本机分享记录？") }, text = {
        Text("只移除 App 中的记录，不会删除网盘里的文件。")
    }, confirmButton = { TvTextButton(onClick = { onRemove(share); deleting = null }) { Text("移除记录") } }, dismissButton = { TvTextButton(onClick = { deleting = null }) { Text("取消") } }) }
    selectedFile?.let { file -> AlertDialog(onDismissRequest = { selectedFile = null }, title = { Text(file.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (file.video) "已识别视频文件。原生取流和账号授权尚未接入，本阶段不能在 App 内播放。" else "这个文件不是当前支持识别的视频格式。")
            Text("大小：${file.size.coerceAtLeast(0) / 1024 / 1024} MiB", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TvTextButton(onClick = { selectedFile = null }) { Text("关闭") } }) }
}
