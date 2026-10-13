package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
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
    val state by store.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(channel.id) { while (true) { now = System.currentTimeMillis(); delay(60000) } }
    val programmes = state.entries(channel)
    val current = programmes.firstOrNull { now >= it.start && now < it.end }
    val next = programmes.firstOrNull { it.start > now }
    if (detailed) {
        Text("节目单", style = MaterialTheme.typography.titleSmall)
        if (programmes.isEmpty()) Text("暂无匹配节目；可在设置中刷新或更换节目单来源。", style = MaterialTheme.typography.bodySmall)
        programmes.filter { it.end > now }.take(8).forEach { p -> Text("${clock(p.start)}–${clock(p.end)} ${p.title}", style = MaterialTheme.typography.bodySmall) }
    } else if (current != null || next != null) {
        Text(current?.let { "正在播：${it.title}" } ?: next?.let { "下一节目：${clock(it.start)} ${it.title}" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
