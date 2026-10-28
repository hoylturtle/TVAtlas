package com.tvatlas.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvatlas.player.PlayerViewModel
import kotlinx.coroutines.*

@Composable internal fun StremioAddonsPage(model: PlayerViewModel, onBack: () -> Unit) {
    val state by model.stremioAddons.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var addJob by remember { mutableStateOf<Job?>(null) }
    fun change(action: suspend () -> Unit) { scope.launch { try { action(); error = null } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = "插件配置暂未保存，请重试" } } }
    BackHandler { onBack() }
    DisposableEffect(Unit) { onDispose { addJob?.cancel() } }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VodLibraryToolbar("影视插件", "Stremio HTTP 插件 · 与现有影视来源共同使用", false, onBack = onBack)
        Row(Modifier.fillMaxWidth().background(AtlasPageStyle.toolbar, AtlasPageStyle.corner)) {
            TvTextButton(onClick = { address = ""; adding = true }, enabled = !state.loading && !state.busy) { Text("添加插件") }
            TvTextButton(onClick = { address = "https://v3-cinemeta.strem.io/manifest.json"; adding = true }, enabled = !state.loading && !state.busy) { Text("全球片目 · Cinemeta") }
        }
        Text("片目不等于可播放。Cinemeta 提供资料；播放需另有兼容的线路插件。新增来源的广告情况默认未知。", style = MaterialTheme.typography.bodyMedium)
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.loading && state.entries.isEmpty()) Text("尚未添加插件。可添加提供方给出的 manifest.json 地址；账号由你自己配置。")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.entries, key = { it.source }) { entry ->
                Column(Modifier.fillMaxWidth().background(AtlasPageStyle.toolbar, AtlasPageStyle.corner).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.manifest.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.manifest.capability, style = MaterialTheme.typography.bodySmall)
                    Text("${entry.manifest.catalogs.size} 个目录 · " + if (entry.enabled) "已启用" else "已停用", style = MaterialTheme.typography.labelSmall)
                    Row {
                        TvTextButton(onClick = { change { model.setStremioAddonEnabled(entry.source, !entry.enabled) } }, enabled = !state.busy) { Text(if (entry.enabled) "停用" else "启用") }
                        TvTextButton(onClick = { change { model.removeStremioAddon(entry.source) } }, enabled = !state.busy) { Text("移除") }
                    }
                }
            }
        }
        Text("第一期支持目录、搜索、选集及 HTTP 视频；BT、磁力、外部网页与压缩包来源暂不支持。不会发送观看事件给插件。", style = MaterialTheme.typography.labelSmall)
    }
    if (adding) AlertDialog(onDismissRequest = { addJob?.cancel(); adding = false; address = "" }, title = { Text("添加影视插件") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("填写配置完成的 manifest.json 地址。地址可能包含密钥，只保存在本机加密配置中。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(address, { address = it.take(4096) }, Modifier.fillMaxWidth().testTag("addon-address"), label = { Text("插件地址") }, singleLine = true)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.busy) Text("正在读取插件清单…")
        }
    }, confirmButton = { TvButton(onClick = {
        model.cloudNetworkAction {
            addJob = scope.launch {
                model.addStremioAddon(address)
                if (model.stremioAddons.state.value.error == null) { adding = false; address = "" }
            }
        }
    }, enabled = !state.busy && address.isNotBlank()) { Text("验证并添加") } }, dismissButton = { TvTextButton(onClick = { addJob?.cancel(); adding = false; address = "" }) { Text("取消") } })
}
