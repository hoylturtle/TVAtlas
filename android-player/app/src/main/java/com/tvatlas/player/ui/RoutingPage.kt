package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.player.SubscriptionFeedback
import com.tvatlas.player.proxy.CoreInfo
import com.tvatlas.player.BuildConfig
import com.tvatlas.player.update.*
import com.tvatlas.player.storage.*

@Composable internal fun RoutingPage(library: Library, onImport: () -> Unit, onExport: () -> Unit, onAdd: () -> Unit,
    onEdit: (ProxyProfile) -> Unit, subscriptions: List<SubscriptionRow>, busy: Boolean, onSubscribe: () -> Unit,
    onRefresh: (String) -> Unit, onDelete: (String) -> Unit, onEnable: (ProxyProfile) -> Unit,
    subscriptionRoute: RouteTarget = RouteTarget.AUTO, onSubscriptionRoute: (RouteTarget) -> Unit = {},
    feedback: SubscriptionFeedback? = null, updatingId: String? = null,
    coreInfo: CoreInfo = CoreInfo(BuildConfig.MIHOMO_VERSION, "", false, false),
    onCoreInfo: () -> Unit = {}, onCoreAppUpdate: () -> Unit = {},
    nodeHealth: Map<String, NodeHealth> = emptyMap(), nodeTesting: Boolean = false, onTestNodes: () -> Unit = {}) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var nodeSubscription by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var hongKongOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(section) { if (section == 3) onCoreInfo() }
    val shownNodes = library.profiles.filter { (nodeSubscription == null || it.subscriptionId == nodeSubscription) &&
        (!hongKongOnly || isHongKongLabel(it.name)) && it.name.contains(query, true) }.sortedWith(compareBy<ProxyProfile>(
        { val sample = nodeHealth[it.id]?.takeIf { h -> h.fresh(System.currentTimeMillis()) }; if (sample == null) 1 else if (sample.delayMs != null) 0 else 2 },
        { nodeHealth[it.id]?.takeIf { h -> h.fresh(System.currentTimeMillis()) }?.delayMs ?: Long.MAX_VALUE }))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("代理与订阅", style = MaterialTheme.typography.headlineSmall)
            Text("订阅获取节点，播放时按频道选择使用。", Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TabRow(selectedTabIndex = section) {
                listOf("订阅", "节点", "高级", "内核").forEachIndexed { index, title ->
                    Tab(selected = section == index, onClick = { section = index; nodeSubscription = null },
                        modifier = Modifier.tvFocusOutline(), text = { Text(title) })
                }
            }
        }
        if (section == 0) {
            item {
                TvButton(onClick = onSubscribe, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("添加代理订阅") }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("订阅下载网络", style = MaterialTheme.typography.titleMedium)
                        SubscriptionNetworkPicker(library.profiles, subscriptionRoute, !busy, onSubscriptionRoute)
                        Text("自动：先直连，失败后尝试已有节点，优先香港。此设置只影响订阅下载。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (updatingId != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在下载并校验节点…", style = MaterialTheme.typography.bodySmall) }
                        if (feedback?.id == null && feedback != null) Text(feedback.message,
                            color = if (feedback.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (subscriptions.isEmpty()) item { Text("还没有代理订阅\n添加 Clash / Mihomo YAML 订阅后，节点会显示在“节点”页。", style = MaterialTheme.typography.bodyMedium) }
            items(subscriptions, key = { "subscription:${it.id}" }) { sub ->
                var menu by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Text(sub.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Box {
                                TvTextButton(onClick = { menu = true }, enabled = !busy) { Text("更多") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("删除订阅") }, onClick = { menu = false; onDelete(sub.id) }, modifier = Modifier.tvFocusOutline())
                                }
                            }
                        }
                        val enabled = library.profiles.count { it.subscriptionId == sub.id && it.enabled }
                        Text("${sub.nodeCount} 个节点 · $enabled 个已启用", style = MaterialTheme.typography.bodyMedium)
                        Text("上次成功更新：${date(sub.lastUpdatedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (feedback?.id == sub.id) Text(feedback.message,
                            color = if (feedback.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvOutlinedButton(onClick = { onRefresh(sub.id) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text(if (updatingId == sub.id) "更新中…" else "更新订阅")
                            }
                            TvTextButton(onClick = { nodeSubscription = sub.id; section = 1; query = ""; hongKongOnly = false }, modifier = Modifier.weight(1f)) { Text("查看节点") }
                        }
                    }
                }
            }
        } else if (section == 1) {
            item {
                if (nodeSubscription != null) {
                    Text(subscriptions.firstOrNull { it.id == nodeSubscription }?.name ?: "订阅节点", style = MaterialTheme.typography.titleMedium)
                    TvTextButton(onClick = { nodeSubscription = null }) { Text("查看所有订阅节点") }
                }
                TvOutlinedButton(onClick = onTestNodes, enabled = !busy && !nodeTesting, modifier = Modifier.fillMaxWidth()) { Text(if (nodeTesting) "测速中…" else "测速全部节点") }
                if (nodeTesting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("延迟为通过节点的 HTTPS 响应耗时。大陆直连；港澳、台湾、日本、韩国使用对应地区节点，低延迟优先。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索节点名称") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${shownNodes.size} 个节点", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    TvTextButton(onClick = { hongKongOnly = !hongKongOnly }) { Text(if (hongKongOnly) "显示全部节点" else "只看香港节点") }
                }
                Text("连接失败会切换到同地区其他节点。手动线路与自定义规则优先。", style = MaterialTheme.typography.bodySmall)
            }
            if (shownNodes.isEmpty()) item { Text("没有符合条件的节点") }
            items(shownNodes, key = { it.id }) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    ListItem(headlineContent = { Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column { Text("${if (p.type == ProxyType.MIHOMO) "订阅节点" else p.type.name} · ${if (p.enabled) "已启用" else "已停用"}", style = MaterialTheme.typography.bodySmall)
                            val sample = nodeHealth[p.id]
                            Text(when { sample == null -> "未测速"; !sample.fresh(System.currentTimeMillis()) -> "测速已过期"; sample.delayMs == null -> "不可达"; else -> "延迟：${sample.delayMs} ms" }, style = MaterialTheme.typography.bodySmall)
                        } },
                        trailingContent = {
                            if (p.type == ProxyType.MIHOMO) Switch(modifier = Modifier.tvFocusOutline(), checked = p.enabled, onCheckedChange = { onEnable(p) }, enabled = !busy)
                            else TvTextButton(onClick = { onEdit(p) }, enabled = !busy) { Text("编辑") }
                        })
                }
            }
            item { TvOutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("手动添加 HTTP / SOCKS5") } }
        } else if (section == 2) {
            item {
                Text("播放路由", style = MaterialTheme.typography.titleMedium)
                Text("大陆：直连\n港澳 → 香港 · 台湾 → 台湾\n日本 → 日本 · 韩国 → 韩国\n同地区低延迟优先，连接失败切换其他节点。\n未识别地区：${routeLabel(library.rules.defaultRoute, library.profiles)}\n手动线路与自定义规则优先。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                Text("${library.rules.rules.size} 条自定义规则", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvOutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导入规则") }
                    TvOutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导出规则") }
                }
            }
            items(library.rules.rules, key = { "rule:${it.id}" }) { rule ->
                ListItem(headlineContent = { Text(rule.id) }, supportingContent = { Text(matchLabel(rule.match)) }, trailingContent = { Text(routeLabel(rule.route, library.profiles)) })
            }
        }
        if (section == 3) item {
            CoreManagement(coreInfo, onCoreAppUpdate)
        }

    }
}

@Composable internal fun SubscriptionNetworkPicker(profiles: List<ProxyProfile>, selected: RouteTarget,
    enabled: Boolean, onSelect: (RouteTarget) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TvOutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected.type == RouteType.AUTO) "自动选择下载网络" else routeLabel(selected, profiles), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("自动选择下载网络") }, onClick = { expanded = false; onSelect(RouteTarget.AUTO) }, modifier = Modifier.tvFocusOutline())
            DropdownMenuItem(text = { Text("直连") }, onClick = { expanded = false; onSelect(RouteTarget.DIRECT) }, modifier = Modifier.tvFocusOutline())
            profiles.filter { it.enabled }.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name) }, onClick = { expanded = false; onSelect(RouteTarget.proxy(profile.id)) }, modifier = Modifier.tvFocusOutline())
            }
        }
    }
}
