package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.player.SubscriptionFeedback
import com.tvatlas.player.proxy.CoreInfo
import com.tvatlas.player.proxy.NodeTestProgress
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
    trafficContent: @Composable () -> Unit = {}, currentTarget: RouteTarget? = null, currentSpeed: Long = 0, currentUsage: Map<String, Long> = emptyMap(),
    nodeTestProgress: NodeTestProgress = NodeTestProgress()) {
    var section by rememberSaveable { mutableIntStateOf(if (library.profiles.isEmpty()) 0 else 1) }
    val tv = atlasTv()
    val sectionMemory = rememberSaveableStateHolder()
    var inspectedNode by rememberSaveable { mutableStateOf<String?>(null) }
    var nodeDialog by remember { mutableStateOf(false) }
    var nodeRegion by rememberSaveable { mutableStateOf<String?>(null) }
    var nodeSubscription by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var nodeSearchOpen by rememberSaveable { mutableStateOf(false) }
    var hongKongOnly by rememberSaveable { mutableStateOf(false) }
    var inspectedRuleFile by remember { mutableStateOf<RuleFileRow?>(null) }
    LaunchedEffect(section) { if (section == 4) onCoreInfo() }
    // Keep card positions stable while individual results arrive.
    val orderingHealth = remember(nodeTesting, library.profiles) { nodeHealth }
    val subscriptionNames = remember(subscriptions) { subscriptions.associate { it.id to it.name } }
    val shownNodes = remember(library.profiles, nodeSubscription, hongKongOnly, query, orderingHealth, nodeRegion) { library.profiles.filter { (nodeSubscription == null || it.subscriptionId == nodeSubscription) &&
        (!hongKongOnly || isHongKongLabel(it.name)) && (nodeRegion == null || (playbackRegion(it.name)?.label ?: "其他") == nodeRegion) && it.name.contains(query, true) }.sortedWith(compareBy<ProxyProfile>(
        { val sample = orderingHealth[it.id]?.takeIf { h -> h.fresh(System.currentTimeMillis()) }; if (sample == null) 1 else if (sample.delayMs != null) 0 else 2 },
        { orderingHealth[it.id]?.takeIf { h -> h.fresh(System.currentTimeMillis()) }?.delayMs ?: Long.MAX_VALUE })) }
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    if (tv) Column(Modifier.width(112.dp).fillMaxHeight().focusGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("代理管理", style = MaterialTheme.typography.titleSmall)
        listOf("订阅", "节点", "规则", "流量", "内核").forEachIndexed { index, label ->
            TvTextButton(onClick = { section = index; nodeSubscription = null; inspectedNode = null },
                modifier = Modifier.fillMaxWidth().selectionOutline(section == index).testTag("routing-section:$index")) { Text(label) }
        }
    }
    Column(Modifier.weight(1f)) {
        AtlasHeader(listOf("代理订阅", "代理节点", "路由规则", "流量统计", "代理内核")[section],
            if (section == 1) "卡片查看详情 · 色块跟随焦点 · 使用中节点另有标记" else "地区自动匹配 · 失败自动回退") {
            TvTextButton(onClick = onSubscribe, enabled = !busy) { Text("＋ 订阅") }
        }
        if (!tv) AtlasTabs(listOf("订阅", "节点", "规则", "流量", "内核"), section) { section = it; nodeSubscription = null; inspectedNode = null }
        sectionMemory.SaveableStateProvider(section) {
        LazyVerticalGrid(columns = if (section == 1) GridCells.Adaptive(156.dp) else if (tv && section == 0) GridCells.Adaptive(300.dp) else GridCells.Fixed(1),
            modifier = Modifier.weight(1f).testTag("routing-content"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        if (!tv || section != 1) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("● 地区自动路由", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(currentTarget?.let { playbackRouteLabel(it, library.profiles, subscriptions) } ?: "大陆直连 · 港澳台日韩匹配地区节点", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (section == 0) {
            if (subscriptions.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("还没有代理订阅\n添加 Clash / Mihomo YAML 订阅后，节点会显示在“节点”页。", style = MaterialTheme.typography.bodyMedium) }
            gridItems(subscriptions, key = { "subscription:${it.id}" }) { sub ->
                var menu by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            TvOutlinedButton(onClick = { onRefresh(sub.id) }, enabled = !busy) {
                                Text(if (updatingId == sub.id) "更新中…" else "更新订阅")
                            }
                            TvTextButton(onClick = { nodeSubscription = sub.id; section = 1; query = ""; hongKongOnly = false; nodeRegion = null }) { Text("查看节点") }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().testTag("node-toolbar"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nodeSubscription != null) {
                    Text(subscriptions.firstOrNull { it.id == nodeSubscription }?.name ?: "订阅节点", style = MaterialTheme.typography.titleMedium)
                    TvTextButton(onClick = { nodeSubscription = null }) { Text("查看所有订阅节点") }
                }
                Text("${shownNodes.size} 个节点", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().testTag("node-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvTextButton(onClick = { nodeSearchOpen = !nodeSearchOpen; if (!nodeSearchOpen) query = "" }) { Text("搜索节点") }
                    TvOutlinedButton(onClick = onTestNodes, enabled = !busy && !nodeTesting && library.profiles.any { it.enabled }) { Text(if (nodeTesting) "测试中…" else "测试全部延迟") }
                }
                NodeTestStatus(nodeTestProgress, nodeTesting, library.profiles)
                Column(Modifier.fillMaxWidth().testTag("node-regions")) {
                AtlasTabs(listOf("全部", "香港", "台湾", "日本", "韩国", "其他"), listOf(null, "香港", "台湾", "日本", "韩国", "其他").indexOf(nodeRegion).coerceAtLeast(0)) { index ->
                    nodeRegion = listOf(null, "香港", "台湾", "日本", "韩国", "其他")[index]; inspectedNode = null
                }
                }
                if (nodeSearchOpen) OutlinedTextField(query, { query = it; inspectedNode = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索节点名称") })
                }
            }
            if (shownNodes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有符合条件的节点") }
            gridItems(shownNodes, key = { it.id }) { p ->
                val active = currentTarget?.profile == p.id
                Card(onClick = { inspectedNode = p.id; nodeDialog = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp)
                        .testTag("proxy-node:${p.id}").tvFocusOutline(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subscriptionNames[p.subscriptionId] ?: "手动节点",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val sample = nodeHealth[p.id]
                        val measuring = p.id in nodeTestProgress.activeIds
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (active) "● 使用中" else if (!p.enabled) "已停用" else p.type.name,
                                style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(when { measuring -> "测试中"; sample == null -> "未测试"; !sample.fresh(System.currentTimeMillis()) -> "已过期"; sample.delayMs == null -> "失败"; else -> "${sample.delayMs} ms" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sample?.delayMs == null && sample != null && !measuring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { TvOutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("手动添加 HTTP / SOCKS5") } }
        } else if (section == 2) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("代理规则", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth().testTag("builtin-rules")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("内置地区规则 · 不可修改", style = MaterialTheme.typography.titleSmall)
                        Text("中国大陆 → 直连\n香港、澳门 → 香港节点\n台湾 → 台湾节点\n日本 → 日本节点\n韩国 → 韩国节点\n其他地区 → 自动选择保底节点", style = MaterialTheme.typography.bodyMedium)
                        Text("自定义文件未匹配时，内置规则保底生效。手动线路优先。", style = MaterialTheme.typography.bodySmall)
                        Row {
                            RadioButton(colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary), selected = ruleFiles.none { it.active }, onClick = { onSelectRule(null) }, enabled = !busy, modifier = if (tv) Modifier.tvFocusOutline() else Modifier)
                            TvTextButton(onClick = { onSelectRule(null) }, enabled = !busy, modifier = if (tv) Modifier.tvFocusOutline() else Modifier) { Text("仅使用内置规则") }
                        }
                    }
                }
                Text("自定义规则文件", Modifier.testTag("custom-rule-heading").padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text("可保存多个文件，同时只能选一个生效。导入后自动选中新文件，覆盖匹配到的频道。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvOutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导入规则") }
                    TvOutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导出当前规则") }
                }
                }
            }
            gridItems(ruleFiles, key = { "rulefile:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { file ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        RadioButton(colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary), selected = file.active, onClick = { onSelectRule(file.id) }, enabled = !busy, modifier = if (tv) Modifier.tvFocusOutline() else Modifier)
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (file.active) "当前生效" else "未启用", style = MaterialTheme.typography.bodySmall)
                            TvTextButton(onClick = { inspectedRuleFile = file }) { Text("查看规则详情") }
                            TvTextButton(onClick = { onSelectRule(file.id) }, enabled = !busy && !file.active) { Text("使用此规则文件") }
                        }
                    }
                }
            }
            gridItems(library.rules.rules, key = { "rule:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { rule ->
                ListItem(headlineContent = { Text(rule.id) }, supportingContent = { Text("匹配：${matchLabel(rule.match)}") },
                    trailingContent = { Text("路由：${routeLabel(rule.route, library.profiles)}") })
            }
        }
        if (section == 3) item(span = { GridItemSpan(maxLineSpan) }) { trafficContent() }
        if (section == 4) item(span = { GridItemSpan(maxLineSpan) }) {
            CoreManagement(coreInfo, onCoreAppUpdate)
        }

    }
    }
    }
    }
    if (nodeDialog) AlertDialog(onDismissRequest = { nodeDialog = false },
        title = { Text("节点详情") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NodeInspector(library.profiles.firstOrNull { it.id == inspectedNode }, library, subscriptions, nodeHealth, currentTarget, currentSpeed, currentUsage, busy, onEnable, onEdit)
            }
        }, confirmButton = { TvTextButton(onClick = { nodeDialog = false }) { Text("关闭") } })
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

@Composable private fun NodeTestStatus(progress: NodeTestProgress, testing: Boolean, profiles: List<ProxyProfile>) {
    if (progress.total == 0 && !testing) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (progress.total == 0) "正在准备延迟测试…" else
            "${if (testing) "正在测试" else if (progress.remaining > 0) "测试已结束" else "测试完成"} ${progress.completed}/${progress.total} · 成功 ${progress.succeeded} · 失败 ${progress.failed}" +
                if (!testing && progress.remaining > 0) " · 未完成 ${progress.remaining}" else "",
            style = MaterialTheme.typography.bodySmall)
        if (progress.total > 0) LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        else if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
        val active = remember(progress.activeIds, profiles) { profiles.filter { it.id in progress.activeIds }.map { it.name } }
        if (active.isNotEmpty()) Text("正在测试：${active.joinToString(" / ")}", style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("HTTPS 响应延迟 · 最多 4 个节点并行 · 每节点 ${progress.samplesPerNode} 次${if (progress.samplesPerNode > 1) "取中位数" else ""}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    LinearProgressIndicator(progress = { ratio.toFloat() }, modifier = Modifier.fillMaxWidth(), color = warning,
        trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f), drawStopIndicator = {})
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

@Composable private fun ColumnScope.NodeInspector(profile: ProxyProfile?, library: Library, subscriptions: List<SubscriptionRow>,
    health: Map<String, NodeHealth>, currentTarget: RouteTarget?, speed: Long, usage: Map<String, Long>, busy: Boolean,
    onEnable: (ProxyProfile) -> Unit, onEdit: (ProxyProfile) -> Unit) {
    Text("节点详情", style = MaterialTheme.typography.titleMedium)
    if (profile == null) { Text("选择左侧节点查看详情", style = MaterialTheme.typography.bodyMedium); return }
    Text(profile.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
    Text("订阅：${subscriptions.firstOrNull { it.id == profile.subscriptionId }?.name ?: "手动节点"}", style = MaterialTheme.typography.bodySmall)
    Text("协议：${profile.type.name}", style = MaterialTheme.typography.bodySmall)
    val sample = health[profile.id]?.takeIf { it.fresh(System.currentTimeMillis()) }
    Text(sample?.delayMs?.let { "HTTPS 延迟：$it ms" } ?: "暂无有效测速", style = MaterialTheme.typography.bodyMedium)
    Text("本机累计：${usage["PROXY:${profile.id}"]?.let(::trafficSize) ?: "暂无记录"}", style = MaterialTheme.typography.bodySmall)
    if (currentTarget?.profile == profile.id) Text("当前播放：${trafficSize(speed)}/s", color = MaterialTheme.colorScheme.secondary)
    HorizontalDivider()
    Text("同地区回退候选", style = MaterialTheme.typography.titleSmall)
    val region = playbackRegion(profile.name)
    val peers = region?.let { orderedRegionNodes(library.profiles, it, health, System.currentTimeMillis()) }.orEmpty().filterNot { it.id == profile.id }.take(4)
    peers.forEachIndexed { index, node ->
        Row(Modifier.fillMaxWidth()) {
            Text("${index + 1}", Modifier.width(24.dp), color = MaterialTheme.colorScheme.secondary)
            Text(node.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(health[node.id]?.takeIf { it.fresh(System.currentTimeMillis()) }?.delayMs?.let { "$it ms" } ?: "—", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (peers.isEmpty()) Text("暂无同地区备用节点", style = MaterialTheme.typography.bodySmall)
    Text("按最新测速排列；播放时以实际链路为准。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TvOutlinedButton(onClick = { if (profile.type == ProxyType.MIHOMO) onEnable(profile) else onEdit(profile) }, enabled = !busy) {
        Text(if (profile.type != ProxyType.MIHOMO) "编辑节点" else if (profile.enabled) "停用节点" else "启用节点")
    }
}
