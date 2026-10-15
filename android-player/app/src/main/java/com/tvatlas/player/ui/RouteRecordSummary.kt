package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.Channel
import com.tvatlas.core.player.*
import com.tvatlas.player.storage.Library
import com.tvatlas.player.storage.SubscriptionRow

@Composable internal fun RouteRecordSummary(channel: Channel, library: Library, subscriptions: List<SubscriptionRow>, observations: List<RouteObservation>) {
    val records = RouteReliability.records(observations, channel.id, System.currentTimeMillis())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("近期线路记录", style = MaterialTheme.typography.labelLarge)
        Text("近 7 天，每个播放源／节点最多统计最近 20 次；成功需确认画面进度与媒体数据。", style = MaterialTheme.typography.bodySmall)
        if (records.isEmpty()) Text("尚无近期记录", style = MaterialTheme.typography.bodySmall)
        records.take(8).forEach { record ->
            Text("${streamName(channel, record.streamId, library.playlists)} · ${playbackRouteLabel(record.target, library.profiles, subscriptions)}", style = MaterialTheme.typography.bodySmall)
            val reason = when (record.last.failure) {
                FailureKind.NETWORK -> "网络连接失败"
                FailureKind.HTTP -> "服务器请求失败"
                FailureKind.MANIFEST -> "播放源格式错误"
                FailureKind.SEGMENT -> "媒体数据下载失败"
                FailureKind.DECODER -> "设备解码失败"
                FailureKind.DRM -> "授权或设备初始化失败"
                null -> "未分类失败"
            }
            Text("成功 ${record.successes}／失败 ${record.failures} · 成功率 ${record.successPercent}% · 最近：${if (record.last.success) "播放成功" else reason}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (records.size > 8) Text("显示最近 8 个播放源／节点组合", style = MaterialTheme.typography.labelSmall)
    }
}
