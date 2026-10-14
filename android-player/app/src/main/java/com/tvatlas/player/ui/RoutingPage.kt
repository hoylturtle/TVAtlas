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
    nodeHealth: Map<String, NodeHealth> = emptyMap(), nodeTesting: Boolean = false, onTestNodes: () -> Unit = {},
    ruleFiles: List<RuleFileRow> = emptyList(), onSelectRule: (String?) -> Unit = {},
    trafficContent: @Composable () -> Unit = {}) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var nodeSubscription by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var hongKongOnly by rememberSaveable { mutableStateOf(false) }
    var inspectedRuleFile by remember { mutableStateOf<RuleFileRow?>(null) }
    LaunchedEffect(section) { if (section == 4) onCoreInfo() }
    val shownNodes = remember(library.profiles, nodeSubscription, hongKongOnly, query, nodeHealth) { library.profiles.filter { (nodeSubscription == null || it.subscriptionId == nodeSubscription) &&
        (!hongKongOnly || isHongKongLabel(it.name)) && it.name.contains(query, true) }.sortedWith(compareBy<ProxyProfile>(
        { val sample = nodeHealth[it.id]?.takeIf { h -> h.fresh(System.currentTimeMillis()) }; if (sample == null) 1 else if (sample.delayMs != null) 0 else 2 },
        { nodeHealth[it.id]?.takeIf { h -> h.fresh(System.currentTimeMillis()) }?.delayMs ?: Long.MAX_VALUE })) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("代理与路由", style = MaterialTheme.typography.headlineSmall)
            Text("按地区选择节点，低延迟优先，失败自动回退。", Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp) {
                listOf("订阅", "节点", "规则", "流量", "内核").forEachIndexed { index, title ->
                    Tab(selected = section == index, onClick = { section = index; nodeSubscription = null },
                        selectedContentColor = MaterialTheme.colorScheme.onPrimary, unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.selectionOutline(section == index), text = { Text(title) })
                }
            }
        }
        if (section == 0) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("地区自动路由", style = MaterialTheme.typography.titleMedium)
                        Text("大陆直连 · 港澳／台湾／日本／韩国匹配地区节点", style = MaterialTheme.typography.bodySmall)
                        Text("${subscriptions.size} 个订阅 · ${library.profiles.count { it.enabled }} 个可用配置节点", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                TvButton(onClick = onSubscribe, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("添加代理订阅") }
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
                        SubscriptionQuotaSummary(sub)
                        if (updatingId == sub.id) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在下载并校验节点…", style = MaterialTheme.typography.bodySmall) }
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
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("订阅下载网络", style = MaterialTheme.typography.titleMedium)
                        SubscriptionNetworkPicker(library.profiles, subscriptionRoute, !busy, onSubscriptionRoute)
                        Text("自动：先直连，失败后尝试已有节点，优先香港。用于节点订阅、频道源和节目单刷新，不改变播放线路。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (updatingId != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在下载并校验节点…", style = MaterialTheme.typography.bodySmall) }
                        if (feedback?.id == null && feedback != null) Text(feedback.message,
                            color = if (feedback.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
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
                Text("手动节点为首选，失败后切换同地区备用节点。通用测速成功不代表所有直播地址都可用。", style = MaterialTheme.typography.bodySmall)
            }
            if (shownNodes.isEmpty()) item { Text("没有符合条件的节点") }
            items(shownNodes, key = { it.id }) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    ListItem(headlineContent = { Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column { Text("${if (p.type == ProxyType.MIHOMO) "订阅节点" else p.type.name} · ${if (p.enabled) "已启用" else "已停用"}", style = MaterialTheme.typography.bodySmall)
                            p.subscriptionId?.let { id ->
                                Text("订阅：${subscriptions.firstOrNull { it.id == id }?.name ?: "未知订阅"}", style = MaterialTheme.typography.bodySmall)
                            }
                            val sample = nodeHealth[p.id]
                            Text(when { sample == null -> "未测速"; !sample.fresh(System.currentTimeMillis()) -> "测速已过期"; sample.delayMs == null -> "近期测速 / 连接失败"; else -> "HTTPS 延迟：${sample.delayMs} ms" }, style = MaterialTheme.typography.bodySmall)
                        } },
                        trailingContent = {
                            if (p.type == ProxyType.MIHOMO) Switch(modifier = Modifier.selectionOutline(p.enabled), checked = p.enabled, onCheckedChange = { onEnable(p) }, enabled = !busy)
                            else TvTextButton(onClick = { onEdit(p) }, enabled = !busy) { Text("编辑") }
                        })
                }
            }
            item { TvOutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("手动添加 HTTP / SOCKS5") } }
        } else if (section == 2) {
            item {
                Text("代理规则", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("内置地区规则 · 不可修改", style = MaterialTheme.typography.titleSmall)
                        Text("中国大陆 → 直连\n香港、澳门 → 香港节点\n台湾 → 台湾节点\n日本 → 日本节点\n韩国 → 韩国节点\n其他地区 → 自动选择保底节点", style = MaterialTheme.typography.bodyMedium)
                        Text("自定义文件未匹配时，内置规则保底生效。手动线路优先。", style = MaterialTheme.typography.bodySmall)
                        Row {
                            RadioButton(colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onPrimary), selected = ruleFiles.none { it.active }, onClick = { onSelectRule(null) }, enabled = !busy, modifier = Modifier.selectionOutline(ruleFiles.none { it.active }))
                            TvTextButton(onClick = { onSelectRule(null) }, enabled = !busy) { Text("仅使用内置规则") }
                        }
                    }
                }
                Text("自定义规则文件", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text("可保存多个文件，同时只能选一个生效。导入后自动选中新文件，覆盖匹配到的频道。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvOutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导入规则") }
                    TvOutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导出当前规则") }
                }
            }
            items(ruleFiles, key = { "rulefile:${it.id}" }) { file ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        RadioButton(colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onPrimary), selected = file.active, onClick = { onSelectRule(file.id) }, enabled = !busy, modifier = Modifier.selectionOutline(file.active))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (file.active) "当前生效" else "未启用", style = MaterialTheme.typography.bodySmall)
                            TvTextButton(onClick = { inspectedRuleFile = file }) { Text("查看规则详情") }
                            TvTextButton(onClick = { onSelectRule(file.id) }, enabled = !busy && !file.active) { Text("使用此规则文件") }
                        }
                    }
                }
            }
            items(library.rules.rules, key = { "rule:${it.id}" }) { rule ->
                ListItem(headlineContent = { Text(rule.id) }, supportingContent = { Text("匹配：${matchLabel(rule.match)}") },
                    trailingContent = { Text("路由：${routeLabel(rule.route, library.profiles)}") })
            }
        }
        if (section == 3) item { trafficContent() }
        if (section == 4) item {
            CoreManagement(coreInfo, onCoreAppUpdate)
        }

    }
    inspectedRuleFile?.let { file ->
        val config = remember(file.json) { runCatching {
            com.tvatlas.core.routing.RuleCodec.json.decodeFromString<com.tvatlas.core.routing.RuleConfig>(file.json)
        }.getOrNull() }
        AlertDialog(onDismissRequest = { inspectedRuleFile = null }, title = { Text(file.name) }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(if (file.active) "当前生效；未匹配频道继续使用内置地区规则" else "未启用，以下为文件内容预览") }
                if (config == null) item { Text("无法读取规则内容", color = MaterialTheme.colorScheme.error) }
                else {
                    item { Text("${config.rules.size} 条规则") }
                    items(config.rules, key = { it.id }) { rule ->
                        Column {
                            Text(rule.id, style = MaterialTheme.typography.titleSmall)
                            Text("匹配：${matchLabel(rule.match)}")
                            Text("路由：${routeLabel(rule.route, library.profiles)}")
                            Text("优先级：${rule.priority?.toString() ?: "默认"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TvTextButton(onClick = { inspectedRuleFile = null }) { Text("关闭") } })
    }
}

@Composable private fun SubscriptionQuotaSummary(sub: SubscriptionRow) {
    val upload = sub.uploadBytes
    val download = sub.downloadBytes
    val total = sub.totalBytes
    if (upload == null || download == null || total == null || total <= 0) {
        Text("套餐流量：服务商未提供", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val used = SubscriptionQuota(upload, download, total, sub.expireAt).usedBytes
    val remaining = (total - used).coerceAtLeast(0)
    val ratio = (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    val remainingRatio = 1.0 - ratio
    val warning = when {
        remainingRatio <= 0.10 -> MaterialTheme.colorScheme.error
        remainingRatio <= 0.20 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Text("套餐：已用 ${trafficSize(used)} / ${trafficSize(total)}", style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(progress = { ratio.toFloat() }, modifier = Modifier.fillMaxWidth(), color = warning)
    Text("剩余 ${trafficSize(remaining)}${sub.expireAt?.let { " · 到期 ${date(it)}" }.orEmpty()}",
        style = MaterialTheme.typography.bodySmall, color = warning)
    Text("套餐数据更新：${date(sub.quotaUpdatedAt)} · 可能包含其他设备用量", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (sub.quotaUpdatedAt != null && sub.quotaUpdatedAt < sub.lastUpdatedAt) {
        Text("本次未返回套餐信息，以上为上次记录", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            DropdownMenuItem(text = { Text("自动选择下载网络") }, onClick = { expanded = false; onSelect(RouteTarget.AUTO) }, modifier = Modifier.selectionOutline(selected == RouteTarget.AUTO))
            DropdownMenuItem(text = { Text("直连") }, onClick = { expanded = false; onSelect(RouteTarget.DIRECT) }, modifier = Modifier.selectionOutline(selected == RouteTarget.DIRECT))
            profiles.filter { it.enabled }.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name) }, onClick = { expanded = false; onSelect(RouteTarget.proxy(profile.id)) }, modifier = Modifier.selectionOutline(selected == RouteTarget.proxy(profile.id)))
            }
        }
    }
}
