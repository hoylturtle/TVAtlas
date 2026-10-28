package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvatlas.player.vod.VodState
import com.tvatlas.player.vod.VodPlaybackController
import com.tvatlas.core.vod.VodPlaybackSafety

@Composable internal fun VodLinesDialog(state: VodState, controller: VodPlaybackController, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("本片线路") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("连接可达不等于已出画面；首帧和画质以本机实际播放为准。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.lines, key = { it.id }) { line ->
                    TvTextButton(onClick = { controller.chooseLine(line.id); close() }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text((if (line.id == state.currentLine) "当前 · " else "") + line.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(line.route, line.status, line.advertisement, line.height?.let { "${it}P" }, line.firstFrameMs?.let { "首帧 ${it}ms" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text("不同剪辑版本可能有时长差异；本机跳过片段不会自动套用到其他地址。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TvTextButton(onClick = close) { Text("关闭") } })
}

@Composable internal fun VodSkipDialog(state: VodState, controller: VodPlaybackController, close: () -> Unit) {
    var start by remember(state.item?.url) { mutableStateOf("") }
    var end by remember(state.item?.url) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("手动跳过片段") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅用于当前视频地址，不识别或删除画面。启用后可撤销本次跳过。", style = MaterialTheme.typography.bodySmall)
            Row {
                Text("自动跳过已标记区间", Modifier.weight(1f))
                Switch(state.skipsEnabled, controller::setSkipsEnabled, enabled = state.skipRanges.isNotEmpty())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it.take(12) }, Modifier.weight(1f), label = { Text("起点（秒）") }, singleLine = true)
                OutlinedTextField(end, { end = it.take(12) }, Modifier.weight(1f), label = { Text("终点（秒）") }, singleLine = true)
            }
            Row {
                TvTextButton(onClick = { start = ((state.player?.currentPosition ?: 0) / 1000).toString() }) { Text("当前位置作起点") }
                TvTextButton(onClick = { end = ((state.player?.currentPosition ?: 0) / 1000).toString() }) { Text("当前位置作终点") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TvButton(onClick = {
                val a = start.toLongOrNull(); val b = end.toLongOrNull()
                try {
                    require(a != null && b != null && a in 0..86_400_000 && b in 0..86_400_000)
                    VodPlaybackSafety.validateSkip(a * 1000, b * 1000, state.player?.duration ?: 0)
                    controller.saveSkip(a * 1000, b * 1000); error = null; start = ""; end = ""
                } catch (_: IllegalArgumentException) { error = "请输入视频时长内的起止秒数，区间至少 1 秒" }
            }, enabled = state.seekable && !state.live) { Text("保存并启用") }
            LazyColumn(Modifier.heightIn(max = 140.dp)) {
                items(state.skipRanges, key = { "${it.startMs}:${it.endMs}" }) { range ->
                    Row {
                        Text("${range.startMs / 1000}–${range.endMs / 1000} 秒", Modifier.weight(1f))
                        TvTextButton(onClick = { controller.removeSkip(range) }) { Text("移除") }
                    }
                }
            }
        }
    }, confirmButton = { TvTextButton(onClick = close) { Text("关闭") } })
}
