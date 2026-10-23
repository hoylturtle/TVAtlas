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

data class VodLineInfo(val id: String, val name: String, val route: String, val status: String = "未验证", val height: Int? = null, val firstFrameMs: Long? = null)
data class VodState(val item: VodItem? = null, val player: ExoPlayer? = null,
    val playing: Boolean = false, val ready: Boolean = false, val live: Boolean = false,
    val feedback: String? = null, val insertionMarkers: Boolean? = null, val alternatives: Int = 0,
    val error: String? = null, val qualityHeight: Int? = null, val resumeWarning: String? = null, val routeLabel: String = "直连", val seekable: Boolean = false, val lines: List<VodLineInfo> = emptyList(), val currentLine: String? = null,
    val skipRanges: List<VodSkipRange> = emptyList(), val skipsEnabled: Boolean = false, val undoSkipPosition: Long? = null, val resumeProposal: Long? = null, val versionChanged: Boolean = false)

/** All player commands run on the main thread. Source URLs never enter logs or persistence. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodPlaybackController(private val context: Context, private val scope: CoroutineScope,
    client: OkHttpClient, private val storeFactory: () -> VodProgressStorage, private val canPlay: () -> Boolean,
    private val log: com.tvatlas.player.debug.DebugLog? = null,
    private val onEnded: () -> Unit = {}, private val onClosed: () -> Unit = {},
    private val onMeasuredQuality: (VodItem, Int, String) -> Unit = { _, _, _ -> },
    private val onMediaFailure: (VodItem, String, Int) -> Unit = { _, _, _ -> },
    private val onStarted: () -> Unit = {}) {
    private val _state = MutableStateFlow(VodState())
    val state = _state.asStateFlow()
    private val adPreferences = context.getSharedPreferences("vod-reported-ad-addresses", Context.MODE_PRIVATE)
    private val skips = VodSkipStore(context)
    private var skipJob: Job? = null
    private var suppressedRange: VodSkipRange? = null
    private var lastSkipRange: VodSkipRange? = null
    private var lineItems = emptyList<VodItem>()
    private var lineClients = emptyMap<String, OkHttpClient>()
    private var lineRoutes = emptyMap<String, String>()
    private var namesByUrl = emptyMap<String, String>()
    private val lineResults = mutableMapOf<String, VodLineInfo>()
    private fun lineSnapshot(current: VodItem): List<VodLineInfo> = lineItems.mapIndexed { index, item ->
        val id = adKey(item.url)
        (lineResults[id] ?: VodLineInfo(id, namesByUrl[item.url] ?: "线路 ${index + 1}", lineRoutes[item.url] ?: routeLabel))
            .let { if (reported(item)) it.copy(status = "已标记有广告") else if (item.url == current.url && it.status == "未验证") it.copy(status = "连接中") else it }
    }
    private fun updateLine(item: VodItem, status: String, height: Int? = null, firstFrameMs: Long? = null) {
        val id = adKey(item.url)
        val previous = lineSnapshot(item).firstOrNull { it.id == id } ?: return
        lineResults[id] = previous.copy(status = status, height = height ?: previous.height, firstFrameMs = firstFrameMs ?: previous.firstFrameMs)
        _state.value = _state.value.copy(lines = lineSnapshot(item))
    }
    private var reportAction: (() -> Unit)? = null
    private fun adKey(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun reported(item: VodItem) = adPreferences.getBoolean(item.advertisementKey, false) ||
        adPreferences.getBoolean(adKey(item.url), false)
    private fun playAllowed() = _state.value.item?.url?.startsWith("content://") == true || canPlay()
    private var active: ExoPlayer? = null
    private var generation = 0L
    private var openJob: Job? = null
    private var bufferJob: Job? = null
    private val historyLock = Mutex()
    private var store: VodProgressStorage? = null
    private var lastSaved: Pair<String, VodBookmark>? = null
    private var transport = client
    private var routeLabel = "直连"
    private fun mediaClient(): OkHttpClient {
        val token = generation
        return vodMediaClient(transport, canPlay, log) { found ->
            scope.launch {
                if (token == generation) _state.value = _state.value.copy(
                    insertionMarkers = found || _state.value.insertionMarkers == true)
            }
        }
    }
    init { scope.launch { while (isActive) { delay(5000); checkpoint() } } }
    fun open(item: VodItem, client: OkHttpClient? = null, label: String? = null, alternatives: List<VodItem> = emptyList(), resumePosition: Long = 0,
        alternativeClients: Map<String, OkHttpClient> = emptyMap(), alternativeLabels: Map<String, String> = emptyMap(), lineNames: Map<String, String> = emptyMap()) {
        val eligible = (listOf(item) + alternatives).filterNot(::reported)
        if (eligible.isEmpty()) {
            releasePlayer()
            _state.value = VodState(item = item, error = "这些视频地址已被你标记为有广告，暂无其他线路。可撤销广告标记后重试。")
            return
        }
        if (eligible.first().url != item.url || eligible.size != alternatives.size + 1) {
            open(eligible.first(), client, label, eligible.drop(1), resumePosition, alternativeClients, alternativeLabels, lineNames)
            return
        }
        val previousDuration = active?.duration ?: 0L
        val expectedDuration = if (resumePosition > 0) previousDuration else 0L
        val sameEpisode = _state.value.item?.key == item.key || resumePosition > 0 && lineItems.any { it.url == item.url }
        if (!sameEpisode || lineItems.none { it.url == item.url }) { lineItems = eligible; lineResults.clear() }
        if (!sameEpisode) { lineClients = emptyMap(); lineRoutes = emptyMap(); namesByUrl = emptyMap() }
        if (alternativeClients.isNotEmpty()) lineClients = alternativeClients
        if (alternativeLabels.isNotEmpty()) lineRoutes = alternativeLabels
        if (lineNames.isNotEmpty()) namesByUrl = lineNames
        releasePlayer()
        suppressedRange = null
        lastSkipRange = null
        reportAction = {
            val position = active?.currentPosition?.coerceAtLeast(0) ?: 0
            val key = item.advertisementKey
            adPreferences.edit().putBoolean(key, true).apply()
            log?.event("INFO", "VOD_PLAYER", "user reported embedded advertisement; alternatives=${alternatives.size}")
            val available = alternatives.filterNot(::reported)
            if (available.isNotEmpty() && playAllowed()) {
                open(available.first(), transport, routeLabel, available.drop(1), position, alternativeClients, alternativeLabels, lineNames)
                notice("已标记，正在尝试备用线路；不同版本进度可能有偏差，可快退调整")
            } else {
                pause()
                notice("已标记此视频地址有广告并暂停，暂无可用的其他线路。")
            }
        }
        if (alternativeClients[item.url] != null) transport = alternativeClients.getValue(item.url) else if (client != null) transport = client
        if (alternativeLabels[item.url] != null) routeLabel = alternativeLabels.getValue(item.url) else if (label != null) routeLabel = label
        log?.event("INFO", "VOD_PLAY", "requested host=${java.net.URI(item.url).host} route=$routeLabel")
        val token = ++generation
        val currentId = adKey(item.url)
        lineResults[currentId]?.let { lineResults[currentId] = it.copy(status = "连接中", firstFrameMs = null) }
        val skipSettings = skips.read(item.url)
        _state.value = VodState(item = item, routeLabel = routeLabel, alternatives = alternatives.count { it.advertisementKey != item.advertisementKey },
            lines = lineSnapshot(item), currentLine = adKey(item.url), skipRanges = skipSettings.ranges, skipsEnabled = skipSettings.enabled)
        openJob = scope.launch {
            var warning: String? = null
            val saved = try { historyLock.withLock { withContext(Dispatchers.IO) {
                (store ?: storeFactory().also { store = it }).load(item.key)
            } } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warning = "续播记录暂不可用，本次从头播放"; null }
            if (token != generation) return@launch
            if (!playAllowed()) { _state.value = _state.value.copy(error = "网络条件已变化，请确认后继续播放"); return@launch }
            val player = ExoPlayer.Builder(context).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).setMediaSourceFactory(DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context, OkHttpDataSource.Factory(mediaClient()).setUserAgent("Mozilla/5.0 TVAtlas")))).build()
            active = player
            val preparingAt = android.os.SystemClock.elapsedRealtime()
            var firstFrameRecorded = false
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
                            if (active === player && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && playAllowed()) {
                                updateLine(item, "缓冲超时")
                                open(alternatives.first(), transport, routeLabel, alternatives.drop(1), player.currentPosition.coerceAtLeast(0), alternativeClients, alternativeLabels, lineNames)
                                notice("缓冲较久，已切换备用线路")
                            }
                        }
                    }
                    if (playbackState == Player.STATE_READY && !restored) {
                        restored = true
                        onStarted()
                        if (!player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable) {
                            val position = if (resumePosition > 0) resumePosition else VodResume.position(saved, player.duration)
                            if (resumePosition > 0 && VodPlaybackSafety.needsResumeConfirmation(expectedDuration, player.duration)) {
                                _state.value = _state.value.copy(resumeWarning = "这条线路的影片时长不同，请核对版本后选择是否继续原进度", versionChanged = true, resumeProposal = position.takeIf { it < player.duration - 1000 })
                                player.pause()
                            } else if (position > 0) player.seekTo(position.coerceAtMost((player.duration - 1000).coerceAtLeast(0)))
                        }
                    }
                    if (playbackState == Player.STATE_ENDED) checkpoint(finished = true)
                    publish(player)
                    if (playbackState == Player.STATE_ENDED && !player.isCurrentMediaItemLive) scope.launch {
                        if (active === player && token == generation && playAllowed()) onEnded()
                    }
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (active !== player || videoSize.width <= 0 || videoSize.height <= 0) {
                        return
                    }
                    val height = minOf(videoSize.width, videoSize.height)
                    _state.value = _state.value.copy(qualityHeight = height)
                    onMeasuredQuality(item, height, routeLabel)
                }
                override fun onRenderedFirstFrame() {
                    if (active === player && !firstFrameRecorded) {
                        firstFrameRecorded = true
                        updateLine(item, "已出画面", player.videoSize.height.takeIf { it > 0 }, android.os.SystemClock.elapsedRealtime() - preparingAt)
                    }
                }
                override fun onTimelineChanged(timeline: Timeline, reason: Int) { publish(player) }
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { publish(player) }
                override fun onIsPlayingChanged(isPlaying: Boolean) { publish(player) }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (active !== player) return
                    if (playWhenReady && !playAllowed()) {
                        player.pause()
                        _state.value = _state.value.copy(error = "请使用上方播放按钮确认当前网络后继续")
                    } else if (!playWhenReady) checkpoint()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (active !== player) return
                    updateLine(item, "失败 · ${error.errorCode}")
                    onMediaFailure(item, routeLabel, error.errorCode)
                    if (alternatives.isNotEmpty() && playAllowed()) {
                        val position = player.currentPosition.coerceAtLeast(0)
                        log?.event("WARN", "VOD_FAILOVER", "remaining=${alternatives.size} code=${error.errorCode}")
                        scope.launch {
                            if (active === player) {
                                open(alternatives.first(), transport, routeLabel, alternatives.drop(1), position, alternativeClients, alternativeLabels, lineNames)
                                notice("连接失败，已切换备用线路")
                            }
                        }
                        return
                    }
                    val http = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    log?.event("ERROR", "VOD_PLAYER", "code=${error.errorCode} name=${error.errorCodeName} http=$http")
                    log?.error("VOD_PLAYER", error)
                    _state.value = _state.value.copy(playing = false, error = when {
                        error.errorCode in 4001..4005 -> "设备暂不支持此视频编码，可尝试其他版本或在 NAS 端转码"
                        item.url.startsWith("content://") && error.errorCode in 2000..2999 -> "文件读取失败，请检查 U 盘连接或重新授权文件夹"
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
            skipJob = scope.launch {
                while (isActive && active === player) {
                    delay(250)
                    val current = _state.value
                    if (!current.skipsEnabled || !player.isPlaying || !player.isCurrentMediaItemSeekable) continue
                    val position = player.currentPosition
                    if (suppressedRange?.let { position >= it.endMs } == true) suppressedRange = null
                    val range = current.skipRanges.firstOrNull { position >= it.startMs && position < it.endMs && it.endMs <= player.duration && it != suppressedRange } ?: continue
                    lastSkipRange = range
                    _state.value = current.copy(undoSkipPosition = position)
                    player.seekTo(range.endMs)
                    scope.launch {
                        delay(8000)
                        if (active === player && _state.value.undoSkipPosition == position) _state.value = _state.value.copy(undoSkipPosition = null)
                    }
                }
            }
        }
    }
    private fun publish(player: ExoPlayer) {
        if (active === player) _state.value = _state.value.copy(playing = player.isPlaying,
            ready = player.playbackState == Player.STATE_READY, live = player.isCurrentMediaItemLive,
            seekable = player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }
    fun toggle() {
        val p = active ?: run { _state.value.item?.let(::open); return }
        if (p.isPlaying || p.playWhenReady) pause() else if (playAllowed()) {
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
        val player = active ?: return
        if (!player.isCurrentMediaItemSeekable || !player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            notice("当前线路不支持快进／快退，可尝试其他线路")
            return
        }
        player.seekTo(VodSeek.target(player.currentPosition, delta, player.duration))
        log?.event("INFO", "VOD_SEEK", "deltaMs=$delta durationKnown=${player.duration > 0}")
        checkpoint()
    }
    fun restart() { active?.let { it.seekTo(0); if (playAllowed()) it.play() }; checkpoint() }
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
    fun chooseLine(id: String) {
        val item = lineItems.firstOrNull { adKey(it.url) == id } ?: return
        if (!playAllowed()) { notice("请先通过播放按钮确认当前网络后再换线"); return }
        if (item.url == _state.value.item?.url && _state.value.ready && _state.value.error == null) return
        val others = lineItems.filter { it.url != item.url && !reported(it) }
        if (reported(item)) { notice("这条线路已标记有广告，请先清除标记"); return }
        open(item, transport, lineRoutes[item.url] ?: routeLabel, others, active?.currentPosition ?: 0,
            lineClients, lineRoutes, namesByUrl)
        notice("已切换线路；不同剪辑版本的进度可能有偏差")
    }
    fun confirmResume() {
        val position = _state.value.resumeProposal ?: return
        active?.let { if (position < it.duration && playAllowed()) { it.seekTo(position); it.play() } }
        _state.value = _state.value.copy(resumeProposal = null, resumeWarning = null, versionChanged = false)
    }
    fun resumeFromBeginning() {
        _state.value = _state.value.copy(resumeProposal = null, resumeWarning = null, versionChanged = false)
        restart()
    }
    fun saveSkip(startMs: Long, endMs: Long) {
        val player = active ?: return; val item = _state.value.item ?: return
        try {
            require(player.isCurrentMediaItemSeekable && !player.isCurrentMediaItemLive)
            val range = VodPlaybackSafety.validateSkip(startMs, endMs, player.duration)
            val ranges = VodPlaybackSafety.merge(_state.value.skipRanges + range)
            skips.save(item.url, VodSkipSettings(true, ranges))
            _state.value = _state.value.copy(skipRanges = ranges, skipsEnabled = true)
        } catch (_: IllegalArgumentException) { notice("跳过区间须在视频时长内，且至少 1 秒") }
    }
    fun setSkipsEnabled(enabled: Boolean) {
        val item = _state.value.item ?: return
        skips.save(item.url, VodSkipSettings(enabled, _state.value.skipRanges))
        _state.value = _state.value.copy(skipsEnabled = enabled)
    }
    fun removeSkip(range: VodSkipRange) {
        val item = _state.value.item ?: return; val ranges = _state.value.skipRanges - range
        skips.save(item.url, VodSkipSettings(_state.value.skipsEnabled && ranges.isNotEmpty(), ranges))
        _state.value = _state.value.copy(skipRanges = ranges, skipsEnabled = _state.value.skipsEnabled && ranges.isNotEmpty())
    }
    fun undoSkip() {
        val position = _state.value.undoSkipPosition ?: return
        suppressedRange = lastSkipRange; active?.seekTo(position)
        _state.value = _state.value.copy(undoSkipPosition = null)
    }
    fun reportAdvertisement() { reportAction?.invoke() }
    fun clearAdvertisementReports() {
        adPreferences.edit().clear().apply()
        notice("已清除本机的广告地址标记，可重新选择影片播放")
    }
    fun dismissNotice() { _state.value = _state.value.copy(feedback = null) }
    fun notice(message: String) {
        _state.value = _state.value.copy(feedback = message)
        val token = generation
        scope.launch { delay(8000); if (token == generation && _state.value.feedback == message) dismissNotice() }
    }
    fun close() { releasePlayer(); lineItems = emptyList(); lineClients = emptyMap(); lineRoutes = emptyMap(); namesByUrl = emptyMap(); lineResults.clear(); onClosed() }
    private fun releasePlayer() {
        skipJob?.cancel()
        checkpoint(finished = active?.playbackState == Player.STATE_ENDED); generation++; openJob?.cancel(); bufferJob?.cancel()
        reportAction = null
        val old = active; active = null; old?.release()
        _state.value = VodState()
    }
}
