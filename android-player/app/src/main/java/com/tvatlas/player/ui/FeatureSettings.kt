package com.tvatlas.player.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvatlas.core.model.httpUri
import com.tvatlas.player.PlayerViewModel
import com.tvatlas.player.storage.NetworkAccess

@Composable internal fun FeatureSettings(model: PlayerViewModel) {
    val options by model.options.collectAsStateWithLifecycle()
    val guide by model.epg.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var epgEdit by remember { mutableStateOf(false) }
    var quotaEdit by remember { mutableStateOf(false) }
    var passwordPrompt by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && password.length >= 8) model.exportBackup(uri, password.toCharArray())
        password = ""
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; passwordPrompt = true }
    }
    FeatureCard("节目单") {
        Text("XMLTV + TVB、ViuTV 官方节目表补充；地址留空可停用 XMLTV", style = MaterialTheme.typography.bodySmall)
        Text(if (guide.loading) "正在更新节目单…" else guide.error ?: "已缓存 ${guide.guide.programmes.size} 个节目", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvOutlinedButton(onClick = { epgEdit = true }, modifier = Modifier.weight(1f)) { Text("节目单来源") }
            TvTextButton(onClick = model::refreshGuide, enabled = !guide.loading, modifier = Modifier.weight(1f)) { Text("刷新节目单") }
        }
    }
    FeatureCard("自动刷新") {
        PreferenceSwitch("到期刷新频道源与代理订阅", options.autoRefresh) { enabled -> model.editOptions { it.copy(autoRefresh = enabled) } }
        Text("打开应用及运行期间每 15 分钟检查到期来源；仅非计费网络、未播放时更新。失败保留旧配置。", style = MaterialTheme.typography.bodySmall)
        var menu by remember { mutableStateOf(false) }
        Box {
            TvOutlinedButton(onClick = { menu = true }) { Text("刷新间隔：${options.refreshHours} 小时") }
            DropdownMenu(menu, { menu = false }) { listOf(6, 12, 24, 48).forEach { hours ->
                DropdownMenuItem(text = { Text("$hours 小时") }, modifier = Modifier.selectionOutline(options.refreshHours == hours),
                    onClick = { menu = false; model.editOptions { it.copy(refreshHours = hours) } })
            } }
        }
    }
    FeatureCard("网络与流量保护") {
        var networkMenu by remember { mutableStateOf(false) }
        Box {
            TvOutlinedButton(onClick = { networkMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("网络使用：${options.networkAccess.label}") }
            DropdownMenu(networkMenu, { networkMenu = false }) { NetworkAccess.entries.forEach { access ->
                DropdownMenuItem(text = { Text(access.label) }, modifier = Modifier.selectionOutline(options.networkAccess == access),
                    onClick = { networkMenu = false; model.editOptions { it.copy(networkAccess = access) } })
            } }
        }
        Text("手机和平板按此范围使用网络；电视不限制 Wi-Fi 或有线网络。VPN 按有效网络识别。", style = MaterialTheme.typography.bodySmall)
        PreferenceSwitch("手机移动数据播放／APK 下载前提醒", options.warnMetered) { enabled -> model.editOptions { it.copy(warnMetered = enabled) } }
        TvOutlinedButton(onClick = { quotaEdit = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (options.limitMiB == 0L) "流量提醒：未设置" else "累计 ${options.limitMiB} MiB 时提醒")
        }
        Text("提醒按统计重置后的累计量计算，不自动停止播放。", style = MaterialTheme.typography.bodySmall)
    }
    FeatureCard("配置备份与恢复") {
        Text("包含频道源、订阅地址、节点凭证、规则、收藏和偏好。使用密码加密；忘记密码无法恢复。恢复会覆盖当前配置。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvOutlinedButton(onClick = { importUri = null; password = ""; passwordPrompt = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("加密备份") }
            TvTextButton(onClick = { password = ""; importer.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("恢复配置") }
        }
    }
    if (epgEdit) {
        var value by remember { mutableStateOf(options.epgUrl) }
        AlertDialog(onDismissRequest = { epgEdit = false }, title = { Text("XMLTV 节目单地址") }, text = {
            OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("HTTP(S) XML 或 XML.gz") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
        }, confirmButton = { TvTextButton(onClick = { model.saveGuideUrl(value.trim()); epgEdit = false },
            enabled = value.isBlank() || httpUri(value.trim()) != null) { Text("保存并刷新") } }, dismissButton = { TvTextButton(onClick = { epgEdit = false }) { Text("取消") } })
    }
    if (quotaEdit) {
        var value by remember { mutableStateOf(options.limitMiB.toString()) }
        AlertDialog(onDismissRequest = { quotaEdit = false }, title = { Text("流量提醒（MiB）") }, text = {
            OutlinedTextField(value, { value = it }, label = { Text("0 表示关闭，1024 MiB = 1 GiB") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        }, confirmButton = { TvTextButton(onClick = { value.toLongOrNull()?.let { amount -> model.editOptions { it.copy(limitMiB = amount) } }; quotaEdit = false },
            enabled = value.toLongOrNull()?.let { it in 0..1048576 } == true) { Text("保存") } }, dismissButton = { TvTextButton(onClick = { quotaEdit = false }) { Text("取消") } })
    }
    if (passwordPrompt) AlertDialog(onDismissRequest = { passwordPrompt = false; password = "" },
        title = { Text(if (importUri == null) "设置备份密码" else "恢复加密备份") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (importUri == null) "至少 8 个字符，请自行保管密码。" else "输入备份密码。确认后覆盖当前配置。")
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        }, confirmButton = { TvTextButton(onClick = {
            passwordPrompt = false
            val uri = importUri
            if (uri == null) exporter.launch("TVAtlas-backup.tvatlas") else { model.restoreBackup(uri, password.toCharArray()); password = ""; importUri = null }
        }, enabled = password.length >= 8) { Text(if (importUri == null) "选择保存位置" else "确认恢复") } },
        dismissButton = { TvTextButton(onClick = { passwordPrompt = false; password = ""; importUri = null }) { Text("取消") } })
}
@Composable private fun FeatureCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
    }
}
@Composable private fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange, modifier = Modifier.selectionOutline(checked))
    }
}
