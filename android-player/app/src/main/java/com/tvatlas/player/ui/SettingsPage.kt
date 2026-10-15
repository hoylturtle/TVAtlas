package com.tvatlas.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.player.BuildConfig
import com.tvatlas.player.update.*

@Composable internal fun SettingsPage(diagnostics: Boolean, onDiagnostics: (Boolean) -> Unit,
    update: UpdateState, onCheckUpdate: () -> Unit, onDownload: () -> Unit, onLogs: () -> Unit = {},
    onRepository: () -> Unit = {}, profiles: List<ProxyProfile> = emptyList(),
    updateRoute: RouteTarget = RouteTarget.AUTO, onUpdateRoute: (RouteTarget) -> Unit = {}, onInstall: () -> Unit = {}, onCancel: () -> Unit = {}, trafficContent: @Composable () -> Unit = {}, extrasContent: @Composable () -> Unit = {}, playbackContent: @Composable () -> Unit = {}, backupContent: @Composable () -> Unit = {}, interfaceContent: @Composable () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val tv = LocalTvMode.current ?: ((context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION)
    var linkCopied by remember(update.release?.downloadUrl) { mutableStateOf(false) }
    var repositoryCopied by remember { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var licenses by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<Int?>(if (tv) 0 else null) }
    BackHandler(category != null && !tv) { category = null }
    val categories = listOf("播放与网络", "更新与诊断", "界面与遥控器", "隐私与关于")
    val summaries = listOf("网络范围、流量保护、节目单与刷新", "版本更新、网络选择与调试日志", "焦点提示、遥控器按键与播放操作", "加密备份、配置隐私与开源许可")
    val networkBusy = update.checking || update.downloading
    val release = update.release
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (tv) Column(Modifier.align(Alignment.CenterStart).width(210.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            categories.forEachIndexed { index, title ->
                TvTextButton(onClick = { category = index }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectionOutline(category == index)) {
                    Icon(when (index) { 0 -> AtlasSymbols.Network; 1 -> Icons.Default.Refresh; 2 -> AtlasSymbols.Screen; else -> AtlasSymbols.Privacy }, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Column(Modifier.padding(start = if (tv) 210.dp else 0.dp).widthIn(max = 950.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!tv) {
                if (category == null) AtlasHeader("设置", "播放、网络与应用偏好")
                else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { category = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(categories[category!!], style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("设置", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (category == null) {
                categories.forEachIndexed { index, title ->
                    Card(onClick = { category = index }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).tvFocusOutline(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(when (index) { 0 -> AtlasSymbols.Network; 1 -> Icons.Default.Refresh; 2 -> AtlasSymbols.Screen; else -> AtlasSymbols.Privacy },
                                contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(summaries[index], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("TVAtlas ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                if (tv) Text(categories[category!!], style = MaterialTheme.typography.titleLarge)
            }
            if (category == 0) playbackContent()
            if (category == 1 && BuildConfig.BUILD_TYPE == "preview") SettingsCard {
                Text("独立预览版 #${BuildConfig.PREVIEW_ID}", style = MaterialTheme.typography.titleMedium)
                Text("版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodySmall)
                Text("当前布局识别：${if (tv) "电视" else "手机／平板"}", style = MaterialTheme.typography.bodySmall)
                Text("可与正式版共存，数据相互独立。此版本不连接正式版更新通道。")
            }
            if (category == 1 && BuildConfig.BUILD_TYPE != "preview") SettingsCard {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text("当前版本：${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val state = when {
                    update.checking -> "正在检查更新…"
                    update.downloading -> "正在下载 ${update.downloadProgress}%"
                    update.apkReady -> "安装包已校验，可以安装"
                    update.error != null -> update.error
                    release != null && release.versionCode > BuildConfig.VERSION_CODE -> "发现新版本：${release.versionName}"
                    update.checked && release != null && !release.checkComplete -> "部分更新信息未完成，请重试检查"
                    update.checked && release != null -> "已是最新版本"
                    else -> "尚未检查更新"
                }
                Text(state ?: "尚未检查更新", color = if (update.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("更新网络", style = MaterialTheme.typography.labelLarge)
                UpdateNetworkPicker(profiles, updateRoute, !networkBusy, onUpdateRoute)
                Text("自动模式先直连，失败后尝试节点。选定节点优先使用，失败后尝试其他启用节点；选择直连时仅直连。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
                    if (!release.checkComplete) Text("此安装包已校验发布信息；部分其他版本暂不可达。", style = MaterialTheme.typography.bodySmall)
                    if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodySmall)
                    if (release.minSdk > Build.VERSION.SDK_INT) {
                        Text("新版要求 Android API ${release.minSdk} 或以上，当前设备暂不能升级", color = MaterialTheme.colorScheme.error)
                    } else if (update.apkReady) {
                        TvButton(onClick = onInstall, enabled = !networkBusy, modifier = Modifier.fillMaxWidth()) { Text("安装更新") }
                        Text("覆盖安装保留频道、订阅与路由配置。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        TvButton(onClick = onDownload, enabled = !networkBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (update.downloading) "下载中 ${update.downloadProgress}%" else "下载新版 APK")
                        }
                        if (update.downloading) LinearProgressIndicator(progress = { update.downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("下载后校验文件与签名，再由系统确认安装。", style = MaterialTheme.typography.bodySmall)
                    }
                    TvTextButton(onClick = { clipboard.setText(AnnotatedString(release.downloadUrl)); linkCopied = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (linkCopied) "下载链接已复制" else "复制下载链接")
                    }
                }
                if (release == null || release.versionCode <= BuildConfig.VERSION_CODE) {
                    TvButton(onClick = onCheckUpdate, enabled = !networkBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (update.checking) "检查中…" else "检查更新")
                    }
                } else TvOutlinedButton(onClick = onCheckUpdate, enabled = !networkBusy, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
                if (networkBusy) TvTextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(if (update.downloading) "取消下载" else "取消检查") }
            }
            if (category == 0) trafficContent()
            if (category == 3) { backupContent(); extrasContent() }
            if (category == 1) SettingsCard {
                Text("诊断与日志", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("播放诊断", style = MaterialTheme.typography.bodyLarge)
                        Text("在播放器显示线路和错误详情", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = diagnostics, onCheckedChange = onDiagnostics, modifier = if (tv) Modifier.tvFocusOutline() else Modifier)
                }
                HorizontalDivider()
                TvOutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("调试日志") }
                Text("可查看、复制或导出日志，便于排查问题。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (category == 2) SettingsCard {
                interfaceContent()
                Text("遥控器操作", style = MaterialTheme.typography.titleMedium)
                AtlasKeyRow("↑ ↓", "选择频道", "上下移动焦点；播放时上下换台")
                AtlasKeyRow("← →", "切换区域", "在导航、列表与详情间移动")
                AtlasKeyRow("OK", "确认", "播放频道或打开当前项目")
                AtlasKeyRow("长按 OK", "查看线路", "查看频道来源与代理设置")
                AtlasKeyRow("返回", "返回与收起", "先关闭浮层；直播首页确认退出")
                AtlasKeyRow("菜单", "更多操作", "打开频道菜单或页面操作")
                AtlasKeyRow("主页", "系统主页", "返回电视系统主页")
                Text("白色边框表示遥控器焦点，紫色表示当前选择；正在播放的频道显示播放标记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("手机／平板进入全屏时横屏播放；停止后可重新播放。", style = MaterialTheme.typography.bodyMedium)
            }
            if (category == 3) SettingsCard {
                Text("帮助与关于", style = MaterialTheme.typography.titleMedium)
                Text("TVAtlas · ${if (BuildConfig.BUILD_TYPE == "preview") "独立预览版" else "稳定版"} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                TvOutlinedButton(onClick = onRepository, modifier = Modifier.fillMaxWidth()) { Text("打开 GitHub 仓库") }
                TvTextButton(onClick = { clipboard.setText(AnnotatedString(REPOSITORY_URL)); repositoryCopied = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (repositoryCopied) "仓库链接已复制" else "复制仓库链接")
                }
                Text("仓库由浏览器打开，使用浏览器自身的网络设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                SettingsDisclosure("操作帮助", help, { help = !help }) {
                    Text(if (tv) "频道列表：方向键选择，OK 播放，长按 OK 查看线路。\n播放器：上下换台，长按 OK 查看线路；返回退出全屏。"
                        else "直播：点频道播放，点线路选择播放源与代理。\n频道源：管理频道播放列表。\n代理：添加订阅、查看节点；香港频道默认优先香港节点。\n设置：更新应用与导出日志。", style = MaterialTheme.typography.bodySmall)
                }
                SettingsDisclosure("配置与隐私", privacy, { privacy = !privacy }) {
                    Text("代理凭证加密保存在本机，导出的规则不含用户名或密码。播放历史只保存在本机，日志不会自动上传。", style = MaterialTheme.typography.bodySmall)
                }
                SettingsDisclosure("开源许可", licenses, { licenses = !licenses }) {
                    Text("Mihomo ${BuildConfig.MIHOMO_VERSION} © MetaCubeX / Clash contributors · GPL-3.0。许可证与对应源码随发布包提供；内核按许可证提供，无担保。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable private fun SettingsDisclosure(title: String, expanded: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    TvTextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title, Modifier.weight(1f)); Text(if (expanded) "⌄" else "›")
    }
    if (expanded) content()
}

@Composable internal fun UpdateNetworkPicker(profiles: List<ProxyProfile>, selected: RouteTarget,
    enabled: Boolean, onSelect: (RouteTarget) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TvOutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected.type == RouteType.AUTO) "自动（失败后回退）" else routeLabel(selected, profiles), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(modifier = Modifier.selectionOutline(selected == RouteTarget.AUTO), text = { Text("自动（失败后回退）") }, onClick = { expanded = false; onSelect(RouteTarget.AUTO) })
            DropdownMenuItem(modifier = Modifier.selectionOutline(selected == RouteTarget.DIRECT), text = { Text("直连") }, onClick = { expanded = false; onSelect(RouteTarget.DIRECT) })
            profiles.filter { it.enabled }.forEach { profile ->
                DropdownMenuItem(modifier = Modifier.selectionOutline(selected == RouteTarget.proxy(profile.id)), text = { Text(profile.name) }, onClick = { expanded = false; onSelect(RouteTarget.proxy(profile.id)) })
            }
        }
    }
}
