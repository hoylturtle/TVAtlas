@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tvatlas.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onRemove: (CloudShare) -> Unit, onWebsite: (CloudShare) -> Unit, importRequest: Int = 0) {
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(importRequest) { if (importRequest > 0) importing = true }
    var phoneInput by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<CloudShare?>(null) }
    var selectedFile by remember { mutableStateOf<CloudFile?>(null) }
    BackHandler(state.selected != null && !importing && !phoneInput && deleting == null && selectedFile == null, onBack)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AtlasHeader("影视 · 网盘分享", "独立预览 · 夸克 / 百度网盘 / 115")
        Text("本阶段提供分享保存与目录浏览。账号授权、原生取流和断点续播尚在开发；目录可见不代表可以播放。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(onClick = { importing = true }) { Text("导入分享") }
            TvOutlinedButton(onClick = { phoneInput = true }) { Text("手机扫码") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val current = state.selected
        if (current == null) {
            if (state.shares.isEmpty()) Text("还没有分享，粘贴完整分享文字即可识别链接和提取码。", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.shares, key = { it.key }) { share ->
                    AtlasPanel(Modifier.fillMaxWidth()) {
                        Text(share.name, style = MaterialTheme.typography.titleMedium)
                        Text("${share.provider.label} · 保存在本机", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvTextButton(onClick = { onOpen(share) }) { Text(if (share.provider == CloudProvider.BAIDU) "查看接入状态" else "浏览目录") }
                            TvTextButton(onClick = { onWebsite(share) }) { Text("打开官网") }
                            TvTextButton(onClick = { deleting = share }) { Text("移除记录") }
                        }
                    }
                }
            }
        } else {
            Text(current.name, style = MaterialTheme.typography.titleMedium)
            Text(state.folders.joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvTextButton(onClick = onBack) { Text(if (state.folders.size > 1) "上一级" else "返回分享") }
                TvTextButton(onClick = onRefresh, enabled = !state.loading) { Text("重新读取") }
                TvTextButton(onClick = { onWebsite(current) }) { Text("打开官网") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.loading && state.error == null && state.files.isEmpty()) item { Text("这个文件夹暂未返回文件") }
                items(state.files, key = { "${it.directory}:${it.id}" }) { file ->
                    TvTextButton(onClick = { if (file.directory) onEnter(file) else selectedFile = file }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                            Text(if (file.directory) "文件夹" else if (file.video) "视频 · 原生播放待接入" else "文件",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (file.directory) "›" else "详情")
                    }
                }
                if (state.nextOffset != null) item { TvOutlinedButton(onClick = onMore, enabled = !state.loading) { Text("加载更多") } }
            }
        }
    }
    if (importing) AlertDialog(onDismissRequest = { importing = false; text = ""; code = "" }, title = { Text("导入网盘分享") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
