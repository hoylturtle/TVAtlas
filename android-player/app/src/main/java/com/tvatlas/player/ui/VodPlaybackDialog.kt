@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.tvatlas.player.vod.VodPlaybackController
import com.tvatlas.player.vod.VodState

/** Native Media3 controls provide seeking, pause and remote navigation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun VodPlaybackDialog(state: VodState, controller: VodPlaybackController,
    onToggle: () -> Unit, onRestart: () -> Unit, nextEpisode: String? = null,
    preparing: Boolean = false, autoNext: Boolean = true, onAutoNext: (Boolean) -> Unit = {}, onNext: () -> Unit = {}) {
    val tv = atlasTv()
    var controlsVisible by remember { mutableStateOf(true) }
    var tracks by remember(state.player) { mutableStateOf(false) }
    PlaybackOrientation(true, tv)
    Dialog(onDismissRequest = controller::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            state.player?.let { player ->
                AndroidView(factory = { context -> PlayerView(context).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 5000
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE })
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(false); setShowPreviousButton(false)
                    isFocusable = true; post { requestFocus() }
                } }, modifier = Modifier.fillMaxSize(),
                    update = { it.player = player; it.keepScreenOn = state.playing },
                    onRelease = { it.player = null; it.keepScreenOn = false })
            }
            if (controlsVisible || preparing || state.player == null || state.error != null || state.resumeWarning != null) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .65f)).padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(8.dp)) {
                        Text(state.item?.title.orEmpty(), color = Color.White, maxLines = 1)
                        Text(if (state.live) "${state.routeLabel} · 直播媒体不记录续播" else "${state.routeLabel} · 影视点播", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    }
                    TvTextButton(onClick = controller::close) { Text("关闭点播") }
                }
                FlowRow(Modifier.fillMaxWidth()) {
                    if (nextEpisode != null) {
                        TvTextButton(onClick = { onAutoNext(!autoNext) }) { Text(if (autoNext) "连播：开" else "连播：关") }
                        TvTextButton(onClick = onNext, enabled = !preparing) { Text("下一集") }
                    }
                    TvTextButton(onClick = onToggle) { Text(if (state.playing) "暂停" else "播放") }
                    TvTextButton(onClick = onRestart, enabled = state.ready && !state.live) { Text("从头") }
                    TvTextButton(onClick = { tracks = true }, enabled = state.ready) { Text("音轨／字幕") }
                }
                if (preparing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备播放…", color = Color.LightGray) }
                state.qualityHeight?.let { Text("实际视频分辨率：${it}P", color = Color.LightGray, style = MaterialTheme.typography.labelSmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.resumeWarning?.let { Text(it, color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.player == null && state.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (tracks) state.player?.let { PlaybackTrackDialog(it) { tracks = false } }
    }
}
