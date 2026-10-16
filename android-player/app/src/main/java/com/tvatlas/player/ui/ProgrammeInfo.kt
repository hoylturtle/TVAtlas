package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvatlas.core.model.Channel
import com.tvatlas.player.epg.EpgStore
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private fun clock(at: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
@Composable internal fun ProgrammeInfo(store: EpgStore, channel: Channel, detailed: Boolean = false) {
    val overlay = LocalLiveOverlay.current
    val state by store.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(channel.id) { while (true) { now = System.currentTimeMillis(); delay(60000) } }
    val programmes = state.entries(channel)
    val current = programmes.firstOrNull { now >= it.start && now < it.end }
    val next = programmes.firstOrNull { it.start > now }
    if (detailed) {
        ProgrammeSchedule(programmes, now, overlay)
    } else if (current != null || next != null) {
        Text(current?.let { if (overlay) it.title else "正在播：${it.title}" } ?: next?.let { "下一节目：${clock(it.start)} ${it.title}" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!overlay && current != null && next != null) Text("下一个：${next.title}",
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Shared real-data renderer, also used by deterministic screenshot fixtures. */
@Composable internal fun ProgrammeSchedule(programmes: List<com.tvatlas.core.epg.Programme>, now: Long, overlay: Boolean = LocalLiveOverlay.current) {
    Text(if (overlay) "今日节目" else "节目单", style = MaterialTheme.typography.titleMedium)
    if (overlay) Text(SimpleDateFormat("MM月dd日 EEEE", Locale.SIMPLIFIED_CHINESE).format(Date(now)),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val upcoming = programmes.filter { it.end > now }.take(8)
    if (upcoming.isEmpty()) Text("暂无匹配节目，可在设置中刷新节目单。", style = MaterialTheme.typography.bodySmall)
    upcoming.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = if (overlay) 18.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(clock(p.start), Modifier.width(48.dp), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                val current = now >= p.start && now < p.end
                Text(if (current) "正在播" else "${clock(p.start)}–${clock(p.end)}",
                    modifier = if (current) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .25f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp) else Modifier,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f))
    }
}
