package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun PlaybackTracks(player: ExoPlayer?) {
    var open by remember { mutableStateOf(false) }
    TvTextButton(onClick = { open = true }, enabled = player != null,
        colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)) { Text("画质", maxLines = 1) }
    if (open && player != null) PlaybackTrackDialog(player) { open = false }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun PlaybackTrackDialog(player: ExoPlayer, onlyType: Int? = null,
    onTextEnabledChanged: ((Boolean) -> Unit)? = null, onDismiss: () -> Unit) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onTrackSelectionParametersChanged(value: TrackSelectionParameters) { parameters = value }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (onlyType == C.TRACK_TYPE_TEXT) "字幕" else "画质、音轨与字幕") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("track-list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(C.TRACK_TYPE_VIDEO to "画质", C.TRACK_TYPE_AUDIO to "音轨", C.TRACK_TYPE_TEXT to "字幕").filter { onlyType == null || it.first == onlyType }.forEach { (type, title) ->
                item { Text(title, style = MaterialTheme.typography.titleSmall) }
                item {
                    TvTextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT).build()
                        if (type == C.TRACK_TYPE_TEXT) onTextEnabledChanged?.invoke(false)
                    }, modifier = Modifier.fillMaxWidth().selectionOutline(if (type == C.TRACK_TYPE_TEXT) type in parameters.disabledTrackTypes else parameters.overrides.values.none { it.type == type })) { Text(if (type == C.TRACK_TYPE_TEXT) "关闭字幕" else "自动选择") }
                }
                val groups = tracks.groups.filter { it.type == type }
                if (type == C.TRACK_TYPE_TEXT) item {
                    TvTextButton(onClick = {
                        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, false).setSelectUndeterminedTextLanguage(true)
                        val group = groups.firstOrNull { g -> (0 until g.length).any { g.isTrackSupported(it) } }
                        if (group != null) builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup,
                            listOf((0 until group.length).first { group.isTrackSupported(it) })))
                        player.trackSelectionParameters = builder.build()
                        onTextEnabledChanged?.invoke(true)
                    }, modifier = Modifier.fillMaxWidth().selectionOutline(type !in parameters.disabledTrackTypes)) { Text("开启字幕（自动）") }
                }
                if (groups.isEmpty()) item { Text("当前播放源没有提供可选$title", style = MaterialTheme.typography.bodySmall) }
                groups.forEach { group ->
                    (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                        val format = group.getTrackFormat(index)
                        val label = if (type == C.TRACK_TYPE_VIDEO) (format.height.takeIf { it > 0 }?.let { "${it}p" } ?: "默认画质 ${index + 1}") +
                            (format.bitrate.takeIf { it > 0 }?.let { " · ${it / 1000} kbps" } ?: "")
                            else format.label?.takeIf { it.isNotBlank() } ?: format.language?.takeIf { it.isNotBlank() } ?: "${title} ${index + 1}"
                        item {
                            TvTextButton(onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                                    .setTrackTypeDisabled(type, false).addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                                if (type == C.TRACK_TYPE_TEXT) onTextEnabledChanged?.invoke(true)
                            }, modifier = Modifier.fillMaxWidth().selectionOutline(parameters.overrides[group.mediaTrackGroup]?.trackIndices?.contains(index) == true)) { Text(label) }
                        }
                    }
                }
            }
            if (onlyType == C.TRACK_TYPE_TEXT) item {
                Text("关闭后会记住开关偏好。若字幕已嵌入视频画面，则无法在播放器中关闭。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TvTextButton(onClick = onDismiss) { Text("关闭") } })
}
