package com.tvatlas.player.vod

import android.content.Context
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

data class VodState(val item: VodItem? = null, val player: ExoPlayer? = null,
    val playing: Boolean = false, val ready: Boolean = false, val live: Boolean = false,
    val error: String? = null, val qualityHeight: Int? = null, val resumeWarning: String? = null, val routeLabel: String = "直连")

/** All player commands run on the main thread. Source URLs never enter logs or persistence. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodPlaybackController(private val context: Context, private val scope: CoroutineScope,
    client: OkHttpClient, private val storeFactory: () -> VodProgressStorage, private val canPlay: () -> Boolean,
    private val log: com.tvatlas.player.debug.DebugLog? = null,
    private val onEnded: () -> Unit = {}, private val onClosed: () -> Unit = {},
    private val onMeasuredQuality: (VodItem, Int, String) -> Unit = { _, _, _ -> },
    private val onMediaFailure: (VodItem, String, Int) -> Unit = { _, _, _ -> }) {
    private val _state = MutableStateFlow(VodState())
    val state = _state.asStateFlow()
    private var active: ExoPlayer? = null
    private var generation = 0L
    private var openJob: Job? = null
    private var bufferJob: Job? = null
    private val historyLock = Mutex()
    private var store: VodProgressStorage? = null
    private var lastSaved: Pair<String, VodBookmark>? = null
    private var transport = client
    private var routeLabel = "直连"
    private fun mediaClient() = vodMediaClient(transport, canPlay, log)
    init { scope.launch { while (isActive) { delay(5000); checkpoint() } } }
    fun open(item: VodItem, client: OkHttpClient? = null, label: String? = null, alternatives: List<VodItem> = emptyList(), resumePosition: Long = 0,
        alternativeClients: Map<String, OkHttpClient> = emptyMap(), alternativeLabels: Map<String, String> = emptyMap()) {
        releasePlayer()
        if (alternativeClients[item.url] != null) transport = alternativeClients.getValue(item.url) else if (client != null) transport = client
        if (alternativeLabels[item.url] != null) routeLabel = alternativeLabels.getValue(item.url) else if (label != null) routeLabel = label
        log?.event("INFO", "VOD_PLAY", "requested host=${java.net.URI(item.url).host} route=$routeLabel")
        val token = ++generation
        _state.value = VodState(item = item, routeLabel = routeLabel)
        openJob = scope.launch {
            var warning: String? = null
            val saved = try { historyLock.withLock { withContext(Dispatchers.IO) {
                (store ?: storeFactory().also { store = it }).load(item.key)
            } } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warning = "续播记录暂不可用，本次从头播放"; null }
            if (token != generation) return@launch
            if (!canPlay()) { _state.value = _state.value.copy(error = "网络条件已变化，请确认后继续播放"); return@launch }
            val player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(mediaClient()).setUserAgent("Mozilla/5.0 TVAtlas"))).build()
            active = player
            var restored = false
            player.setAudioAttributes(AudioAttributes.DEFAULT, true)
            player.setHandleAudioBecomingNoisy(true)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (active !== player) return
                    log?.event("INFO", "VOD_PLAYER", "state=$playbackState")
                    bufferJob?.cancel()
                    if (playbackState == Player.STATE_BUFFERING && alternatives.isNotEmpty()) {
                        bufferJob = scope.launch {
                            delay(15000)
                            if (active === player && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && canPlay()) {
                                open(alternatives.first(), transport, routeLabel, alternatives.drop(1), player.currentPosition.coerceAtLeast(0), alternativeClients, alternativeLabels)
                            }
                        }
                    }
                    if (playbackState == Player.STATE_READY && !restored) {
                        restored = true
                        if (!player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable) {
                            val position = if (resumePosition > 0) resumePosition else VodResume.position(saved, player.duration)
                            if (position > 0) player.seekTo(position)
                        }
                    }
                    if (playbackState == Player.STATE_ENDED) checkpoint(finished = true)
                    publish(player)
                    if (playbackState == Player.STATE_ENDED && !player.isCurrentMediaItemLive) scope.launch {
                        if (active === player && token == generation && canPlay()) onEnded()
                    }
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (active !== player || videoSize.width <= 0 || videoSize.height <= 0) return
                    val height = minOf(videoSize.width, videoSize.height)
                    _state.value = _state.value.copy(qualityHeight = height)
                    onMeasuredQuality(item, height, routeLabel)
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { publish(player) }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (active !== player) return
                    if (playWhenReady && !canPlay()) {
                        player.pause()
                        _state.value = _state.value.copy(error = "请使用上方播放按钮确认当前网络后继续")
                    } else if (!playWhenReady) checkpoint()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (active !== player) return
                    onMediaFailure(item, routeLabel, error.errorCode)
                    if (alternatives.isNotEmpty() && canPlay()) {
                        val position = player.currentPosition.coerceAtLeast(0)
                        log?.event("WARN", "VOD_FAILOVER", "remaining=${alternatives.size} code=${error.errorCode}")
                        scope.launch {
                            if (active === player) open(alternatives.first(), transport, routeLabel, alternatives.drop(1), position, alternativeClients, alternativeLabels)
                        }
                        return
                    }
                    val http = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    log?.event("ERROR", "VOD_PLAYER", "code=${error.errorCode} name=${error.errorCodeName} http=$http")
                    log?.error("VOD_PLAYER", error)
                    _state.value = _state.value.copy(playing = false, error = when {
                        http == 403 -> "视频服务器拒绝访问（HTTP 403），可换网络或来源"
                        http == 404 || http == 410 -> "播放地址已失效（HTTP $http），请更换来源或影片"
                        http != null -> "视频服务器返回 HTTP $http，请稍后重试或更换来源"
                        else -> when (error.errorCode) {
                        2004 -> "视频地址暂不可用或已过期，请重新获取地址后重试"
                        3001, 3002, 3003, 3004 -> "地址没有返回可识别的视频，网页地址不能当作视频直链"
                        else -> "点播未成功（${error.errorCode}），可重试或更换视频地址"
                    } })
                    checkpoint()
                }
            })
            _state.value = _state.value.copy(player = player, resumeWarning = warning)
            player.setMediaItem(MediaItem.fromUri(item.url)); player.prepare(); player.play()
        }
    }
    private fun publish(player: ExoPlayer) {
        if (active === player) _state.value = _state.value.copy(playing = player.isPlaying,
            ready = player.playbackState == Player.STATE_READY, live = player.isCurrentMediaItemLive)
    }
    fun toggle() {
        val p = active ?: run { _state.value.item?.let(::open); return }
        if (p.isPlaying || p.playWhenReady) pause() else if (canPlay()) {
            _state.value = _state.value.copy(error = null)
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.prepare()
            }
            p.play()
        }
    }
    fun pause() {
        bufferJob?.cancel()
        if (active == null && _state.value.item != null) {
            generation++; openJob?.cancel()
            _state.value = _state.value.copy(error = "已暂停，点击播放继续")
        } else { active?.pause(); checkpoint() }
    }
    fun seekBy(delta: Long) {
        active?.takeIf { it.isCurrentMediaItemSeekable && !it.isCurrentMediaItemLive }?.let {
            it.seekTo((it.currentPosition + delta).coerceIn(0, it.duration.coerceAtLeast(0)))
        }
    }
    fun restart() { active?.let { it.seekTo(0); if (canPlay()) it.play() }; checkpoint() }
    private fun checkpoint(finished: Boolean = false) {
        val p = active ?: return
        val item = _state.value.item ?: return
        if (p.isCurrentMediaItemLive || !p.isCurrentMediaItemSeekable || p.duration <= 0) return
        val bookmark = VodBookmark(if (finished) 0 else p.currentPosition.coerceAtLeast(0), p.duration)
        val token = generation
        scope.launch {
            try { historyLock.withLock { withContext(Dispatchers.IO) {
                if (lastSaved != (item.key to bookmark)) {
                    (store ?: storeFactory().also { store = it }).save(item.key, bookmark)
                    lastSaved = item.key to bookmark
                }
            } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) _state.value = _state.value.copy(resumeWarning = "续播记录保存失败，播放不受影响") }
        }
    }
    fun notice(message: String) { _state.value = _state.value.copy(resumeWarning = message) }
    fun close() { releasePlayer(); onClosed() }
    private fun releasePlayer() {
        checkpoint(finished = active?.playbackState == Player.STATE_ENDED); generation++; openJob?.cancel(); bufferJob?.cancel()
        val old = active; active = null; old?.release()
        _state.value = VodState()
    }
}
