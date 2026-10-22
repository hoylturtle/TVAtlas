package com.tvatlas.player.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvatlas.player.PlayerViewModel
import com.tvatlas.player.media.*
import kotlinx.coroutines.Job

@Composable internal fun MediaFoldersPage(model: PlayerViewModel, onBack: () -> Unit, onCloud: () -> Unit) {
    val controller = model.mediaFolders
    val state by controller.state.collectAsStateWithLifecycle()
    var connecting by remember { mutableStateOf(false) }
    var addJob by remember { mutableStateOf<Job?>(null) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<MediaFolder?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { controller.addLocal(it) } }
    DisposableEffect(controller) { onDispose { controller.pause(); addJob?.cancel() } }
    BackHandler { if (state.selected != null) controller.back() else onBack() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VodLibraryToolbar(if (state.selected == null) "本地与 NAS" else state.selected!!.name, "只读取所选文件夹，按需浏览", false,
            onBack = { if (state.selected != null) controller.back() else onBack() })
        Row(Modifier.fillMaxWidth().background(AtlasPageStyle.toolbar, AtlasPageStyle.corner)) {
            TvTextButton(onClick = {
                try { picker.launch(null) }
                catch (_: ActivityNotFoundException) { controller.message("当前电视未提供系统目录选择器，可安装支持目录授权的文件管理器，或使用 NAS WebDAV") }
                catch (_: Exception) { controller.message("无法打开目录选择器，请检查系统文件管理器") }
            }) { Text("添加 U 盘／文件夹") }
            TvTextButton(onClick = { connecting = true }) { Text("连接 NAS") }
            TvTextButton(onClick = onCloud) { Text("网盘分享") }
            if (state.selected != null) TvTextButton(onClick = controller::refresh, enabled = !state.loading) { Text("刷新") }
        }
        state.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.selected == null) {
            if (state.roots.isEmpty()) Text("选择 U 盘或本机目录，或连接 NAS 的 WebDAV 文件夹。\n保存目录后，下次可直接进入；不会在启动时自动扫描。", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.roots, key = { it.id }) { folder ->
                    Row(Modifier.fillMaxWidth().background(AtlasPageStyle.toolbar, AtlasPageStyle.corner).padding(8.dp)) {
                        TvTextButton(onClick = { controller.open(folder) }, modifier = Modifier.weight(1f)) {
                            Text(folder.name + if (folder.webDav) " · WebDAV" else " · 已授权目录", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TvTextButton(onClick = { removing = folder }) { Text("移除") }
                    }
                }
            }
        } else {
            Text(state.trail.joinToString(" / ") { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            if (!state.loading && state.entries.isEmpty() && state.error == null) Text("此目录没有可显示的视频或子文件夹")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.entries, key = { it.uri }) { entry ->
                    TvTextButton(onClick = { if (entry.directory) controller.enter(entry) else model.openMediaFile(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (entry.directory) "▸ " else "▶ ") + entry.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!entry.directory && entry.size > 0) Text("${entry.size / (1024 * 1024)} MB", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text("读取文件，不修改或删除原视频。支持的编码取决于设备；SMB 目录可通过系统文件提供器授权，内置 NAS 连接当前使用 WebDAV。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (connecting) AlertDialog(onDismissRequest = { addJob?.cancel(); addJob = null; connecting = false; password = "" },
        title = { Text("连接 NAS · WebDAV") }, text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(address, { address = it.take(2048) }, label = { Text("文件夹地址（http 或 https）") }, singleLine = true)
                OutlinedTextField(username, { username = it.take(256) }, label = { Text("用户名，可留空") }, singleLine = true)
                OutlinedTextField(password, { password = it.take(1024) }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (addJob?.isActive == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TvButton(onClick = {
            addJob = controller.addWebDav(name, address, username, password) { connecting = false; password = "" }
        }, enabled = name.isNotBlank() && address.isNotBlank() && addJob?.isActive != true) { Text("连接并保存") } },
        dismissButton = { TvTextButton(onClick = { addJob?.cancel(); addJob = null; connecting = false; password = "" }) { Text("取消") } })
    removing?.let { folder -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除文件夹？") },
        text = { Text("仅移除本机连接记录和目录授权，不删除原视频。") },
        confirmButton = { TvButton(onClick = { controller.remove(folder); removing = null }) { Text("移除") } },
        dismissButton = { TvTextButton(onClick = { removing = null }) { Text("取消") } }) }
}
