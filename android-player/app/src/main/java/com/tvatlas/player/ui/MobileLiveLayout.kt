package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Both areas have their own bounds; playback content cannot take space from channel browsing. */
@Composable internal fun MobileLiveLayout(showPlayer: Boolean, modifier: Modifier = Modifier,
    picture: @Composable (Modifier) -> Unit, channels: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier) {
        val playerHeight = (maxWidth * (9f / 16f) + 108.dp).coerceAtMost(maxHeight * 0.46f)
        Column(Modifier.fillMaxSize()) {
            if (showPlayer) {
                picture(Modifier.fillMaxWidth().height(playerHeight).testTag("mobile-playback-area"))
                HorizontalDivider()
            }
            channels(Modifier.fillMaxWidth().weight(1f).testTag("mobile-channel-area"))
        }
    }
}

/** One short summary in the player; full diagnostics live in a separately scrollable dialog. */
@Composable internal fun CompactPlaybackDetails(error: String?, details: String) {
    var open by remember(error, details) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(error ?: "播放诊断", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = if (error == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
        TvTextButton(onClick = { open = true }) { Text("详情") }
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(if (error == null) "播放诊断" else "播放失败详情") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).testTag("playback-details-scroll")) {
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            if (details.isNotBlank()) Text(details, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TvTextButton(onClick = { open = false }) { Text("关闭") } })
}
