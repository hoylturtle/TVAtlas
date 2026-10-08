package com.tvatlas.player.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.tvatlas.core.model.*
import com.tvatlas.core.player.*
import com.tvatlas.core.routing.DefaultRouteResolver
import com.tvatlas.player.proxy.ProxyClientPool
import com.tvatlas.player.proxy.RoutedCallFactory
import com.tvatlas.player.storage.PlayerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PlaybackStatus(
    val channelId: String? = null, val message: String = "添加播放列表后，选择频道开始观看",
    val attempt: RouteAttempt? = null, val error: String? = null, val successAt: Long? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCoordinator(
    private val context: Context, private val repository: PlayerRepository,
    private val pool: ProxyClientPool, private val scope: CoroutineScope,
) {
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player = _player.asStateFlow()
    private val _status = MutableStateFlow(PlaybackStatus())
    val status = _status.asStateFlow()
    private var session: FailoverSession? = null
    private var watcher: Job? = null
    private var preparation: Job? = null
    private var generation = 0L

    fun play(channel: Channel, resolver: DefaultRouteResolver, streamId: String? = null) {
        stop(false)
        val token = generation
        _status.value = PlaybackStatus(channel.id, "正在连接 ${channel.name}")
        preparation = scope.launch {
            val history = repository.preferred(channel.id)
            if (token != generation) return@launch
            val newSession = FailoverSession(resolver.channelPlan(channel, history, streamId))
            session = newSession
            attempt(channel, resolver, newSession.start(), token)
        }
    }

    private fun attempt(channel: Channel, resolver: DefaultRouteResolver, route: RouteAttempt?, token: Long) {
        if (token != generation) return
        watcher?.cancel()
        _player.value?.release(); _player.value = null
        if (route == null) {
            _status.value = _status.value.copy(message = "${channel.name} 的可用线路均未成功，请检查播放源或路由设置", attempt = null)
            return
        }
        val stream = channel.streams.first { it.id == route.streamId }
        val mediaRequests = ConcurrentHashMap.newKeySet<String>()
        val mediaBytes = AtomicLong(0)
        val factory = OkHttpDataSource.Factory(RoutedCallFactory(pool, resolver, StreamContext(channel, stream), route.target))
            .setTransferListener(object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    if (isNetwork && dataSpec.uri.toString() in mediaRequests) mediaBytes.addAndGet(bytesTransferred.toLong())
                }
            })
        val instance = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
        _player.value = instance
        _status.value = _status.value.copy(channelId = channel.id, message = "正在连接 ${channel.name}", attempt = route, successAt = null)
        var lastLoadType = C.DATA_TYPE_UNKNOWN
        var loadErrorAt = 0L
        var failing = false
        fun fail(failure: PlaybackFailure) {
            if (token != generation || instance !== _player.value || failing) return
            failing = true
            val reason = failure.kind.name + (failure.httpStatus?.let { " HTTP $it" } ?: "")
            _status.value = _status.value.copy(message = "连接暂不可用，正在尝试其他线路", error = reason)
            scope.launch {
                if (token != generation) return@launch
                repository.recordFailure(channel.id, route, reason)
                if (token == generation) attempt(channel, resolver, session?.fail(failure), token)
            }
        }
        instance.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) mediaRequests.add(loadEventInfo.dataSpec.uri.toString())
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                mediaRequests.remove(loadEventInfo.dataSpec.uri.toString())
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                if (!wasCanceled) { lastLoadType = mediaLoadData.dataType; loadErrorAt = SystemClock.elapsedRealtime() }
            }
        })
        instance.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val kind = when {
                    error.errorCode in 4000..4999 -> FailureKind.DECODER
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> FailureKind.MANIFEST
                    http != null -> FailureKind.HTTP
                    lastLoadType == C.DATA_TYPE_MEDIA && SystemClock.elapsedRealtime() - loadErrorAt < 30000 -> FailureKind.SEGMENT
                    else -> FailureKind.NETWORK
                }
                fail(PlaybackFailure(kind, http))
            }
        })
        instance.setMediaItem(MediaItem.fromUri(route.streamUrl))
        instance.prepare(); instance.playWhenReady = true
        watcher = scope.launch {
            val gate = SuccessGate()
            var waitingSince = SystemClock.elapsedRealtime()
            var previousPosition = 0L
            while (isActive && token == generation && instance === _player.value && !failing) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                val progressing = instance.isPlaying && instance.currentPosition != previousPosition
                previousPosition = instance.currentPosition
                if (progressing || !instance.playWhenReady) waitingSince = now
                if (gate.update(now, progressing, mediaBytes.get())) {
                    repository.recordSuccess(channel.id, route)
                    if (token == generation && instance === _player.value) {
                        _status.value = _status.value.copy(message = "正在播放 ${channel.name}", error = null, successAt = System.currentTimeMillis())
                    }
                }
                if (instance.playWhenReady && now - waitingSince >= 20000) {
                    fail(PlaybackFailure(if (lastLoadType == C.DATA_TYPE_MEDIA) FailureKind.SEGMENT else FailureKind.NETWORK))
                }
            }
        }
    }
    fun stop(showMessage: Boolean = true) {
        generation++
        preparation?.cancel(); watcher?.cancel(); session?.stop(); session = null
        _player.value?.release(); _player.value = null
        if (showMessage) _status.value = _status.value.copy(message = "已停止播放", attempt = null)
    }
}
