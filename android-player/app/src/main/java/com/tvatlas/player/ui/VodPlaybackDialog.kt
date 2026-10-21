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
    var seekMessage by remember { mutableStateOf<String?>(null) }
    var seekRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekRequest) { kotlinx.coroutines.delay(1500); seekMessage = null }
    var confirmAd by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
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
                AndroidView(factory = { context -> VodPlayerView(context) { delta ->
                    controller.seekBy(delta)
                    if (controller.state.value.seekable) {
                        seekMessage = (if (delta > 0) "快进" else "快退") + "${kotlin.math.abs(delta) / 1000}秒"; seekRequest++
                    }
                }.apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 5000
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE })
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(false); setShowPreviousButton(false)
                    setShowFastForwardButton(true); setShowRewindButton(true)
                    isFocusable = true; post { requestFocus() }
                } }, modifier = Modifier.fillMaxSize(),
                    update = { it.player = player; it.keepScreenOn = state.playing },
                    onRelease = { it.player = null; it.keepScreenOn = false })
            }
            if (controlsVisible || preparing || state.player == null || state.error != null || state.resumeWarning != null || state.feedback != null) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .65f)).padding(horizontal = 8.dp)) {
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
                    TvTextButton(onClick = { controller.seekBy(-30_000) }, enabled = state.seekable) { Text("快退30秒") }
                    TvTextButton(onClick = { controller.seekBy(30_000) }, enabled = state.seekable) { Text("快进30秒") }
                    TvTextButton(onClick = onRestart, enabled = state.seekable && !state.live) { Text("从头") }
                    TvTextButton(onClick = { tracks = true }, enabled = state.ready) { Text("音轨／字幕") }
                    TvTextButton(onClick = { confirmAd = true }, enabled = state.player != null) { Text("有广告，换线路") }
                    TvTextButton(onClick = { confirmClear = true }) { Text("清除广告标记") }
                }
                if (tv && state.seekable) Text("菜单收起后，←／→ 快退／快进10秒；长按连续调整", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                if (state.ready && !state.seekable) Text("当前线路不支持拖动进度，可尝试其他线路", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                if (preparing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备播放…", color = Color.LightGray) }
                state.qualityHeight?.let { Text("实际视频分辨率：${it}P", color = Color.LightGray, style = MaterialTheme.typography.labelSmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.feedback?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TvTextButton(onClick = controller::dismissNotice) { Text("知道了") }
                } }
                state.resumeWarning?.let { Text(it, color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
            }
            seekMessage?.let { Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .7f)).padding(16.dp), color = Color.White) }
            if (state.player == null && state.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (confirmAd) AlertDialog(onDismissRequest = { confirmAd = false },
            title = { Text("标记广告并换线路？") },
            text = { Text(when (state.insertionMarkers) {
                true -> "播放列表检测到插入标记，但不能据此确认是广告。"
                false -> "已检查的播放列表片段未发现插入标记，仍无法排除广告已混入视频。"
                null -> "当前无法判断广告是剪进视频还是动态插入。"
            } + "\n本机会避开这条影片线路，并尝试 ${state.alternatives} 条备用线路；没有备用则暂停。不同版本进度可能有偏差。也可取消后手动快进。") },
            confirmButton = { TvTextButton(onClick = { confirmAd = false; controller.reportAdvertisement() }) { Text("标记并换线") } },
            dismissButton = { TvTextButton(onClick = { confirmAd = false }) { Text("取消") } })
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text("清除全部广告标记？") }, text = { Text("本机之前避开的所有视频地址将恢复参与自动选线。") },
            confirmButton = { TvTextButton(onClick = { confirmClear = false; controller.clearAdvertisementReports() }) { Text("清除") } },
            dismissButton = { TvTextButton(onClick = { confirmClear = false }) { Text("取消") } })
        if (tracks) state.player?.let { PlaybackTrackDialog(it) { tracks = false } }
    }
}
