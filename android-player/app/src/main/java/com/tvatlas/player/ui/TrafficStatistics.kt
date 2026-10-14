package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvatlas.player.traffic.*
import com.tvatlas.core.model.ProxyProfile
import com.tvatlas.player.storage.SubscriptionRow
import java.util.Locale

internal fun trafficSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.2f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Scope the one-second statistics updates to this card, rather than recomposing the whole app. */
@Composable internal fun TrafficStatistics(store: TrafficStore, profiles: List<ProxyProfile> = emptyList(),
    subscriptions: List<SubscriptionRow> = emptyList()) {
    val state by store.state.collectAsStateWithLifecycle()
    TrafficStatisticsCard(state, store::reset, profiles, subscriptions)
}

@Composable internal fun TrafficStatisticsCard(state: TrafficSnapshot, onReset: () -> Unit,
    profiles: List<ProxyProfile> = emptyList(), subscriptions: List<SubscriptionRow> = emptyList()) {
    var details by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("流量统计", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { Text("直连"); Text(trafficSize(state.direct), style = MaterialTheme.typography.titleLarge) }
                Column(Modifier.weight(1f)) { Text("代理"); Text(trafficSize(state.proxy), style = MaterialTheme.typography.titleLarge) }
            }
            Text("自 ${date(state.since)} 起累计，包含上传与下载。", style = MaterialTheme.typography.bodySmall)
            Text("统计 TVAtlas 的播放、订阅、更新等 HTTP 正文，不含连接开销、内核自身请求或其他 APP。系统 VPN 下的“直连”仍归为 APP 直连。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvOutlinedButton(onClick = { details = true }, modifier = Modifier.weight(1f)) { Text("节点明细") }
                TvTextButton(onClick = { reset = true }, modifier = Modifier.weight(1f)) { Text("重置统计") }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("流量明细") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.entries.isEmpty()) item { Text("暂无流量记录") }
            val profileById = profiles.associateBy { it.id }
            val subscriptionNames = subscriptions.associate { it.id to it.name }
            val subscriptionUsage = state.entries.filter { it.proxy }.groupBy { entry ->
                profileById[entry.id.removePrefix("PROXY:")]?.subscriptionId
            }.filterKeys { it != null }.mapValues { (_, entries) -> entries.sumOf { it.total } }
            if (subscriptionUsage.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("按订阅", style = MaterialTheme.typography.titleSmall)
                    subscriptionUsage.entries.sortedByDescending { it.value }.forEach { (id, total) ->
                        Text("${id?.let(subscriptionNames::get) ?: "已移除订阅"} · ${trafficSize(total)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                    Text("按节点", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(state.entries.sortedByDescending { it.total }, key = { it.id }) { entry ->
                Column {
                    val profile = profileById[entry.id.removePrefix("PROXY:")]
                    val subscription = profile?.subscriptionId?.let(subscriptionNames::get)
                    Text(if (entry.proxy) listOfNotNull(subscription, entry.name).joinToString(" · ") else "直连",
                        style = MaterialTheme.typography.titleSmall)
                    Text("下载 ${trafficSize(entry.received)} · 上传 ${trafficSize(entry.sent)}", style = MaterialTheme.typography.bodySmall)
                    Text("合计 ${trafficSize(entry.total)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, confirmButton = { TvTextButton(onClick = { details = false }) { Text("关闭") } })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("重置流量统计？") },
        text = { Text("将清空直连及所有代理节点的累计流量，从现在重新统计。频道和代理配置保留。") },
        confirmButton = { TvTextButton(onClick = { onReset(); reset = false }) { Text("重置") } },
        dismissButton = { TvTextButton(onClick = { reset = false }) { Text("取消") } })
}
