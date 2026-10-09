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
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import com.tvatlas.player.source.MyTvSuperSource
import androidx.media3.common.MimeTypes
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

data class PlaybackStatus(
    val channelId: String? = null, val message: String = "添加播放列表后，选择频道开始观看",
    val attempt: RouteAttempt? = null, val lastAttempt: RouteAttempt? = null, val error: String? = null, val successAt: Long? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCoordinator(
    private val context: Context, private val repository: PlayerRepository,
    private val pool: ProxyClientPool, private val scope: CoroutineScope,
    private val log: com.tvatlas.player.debug.DebugLog? = null,
) {
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player = _player.asStateFlow()
    private val _status = MutableStateFlow(PlaybackStatus())
    val status = _status.asStateFlow()
    private var session: FailoverSession? = null
    private var watcher: Job? = null
    private var preparation: Job? = null
    private var generation = 0L
    private val renewed = mutableSetOf<RouteAttempt>()

    fun play(channel: Channel, resolver: DefaultRouteResolver, streamId: String? = null) {
        stop(false)
        renewed.clear()
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

    private suspend fun attempt(channel: Channel, resolver: DefaultRouteResolver, route: RouteAttempt?, token: Long) {
        if (token != generation) return
        watcher?.cancel()
        _player.value?.release(); _player.value = null
        if (route == null) {
            log?.event("ERROR", "PLAY", "all routes exhausted channel=${channel.name} detail=${_status.value.error}")
            _status.value = _status.value.copy(message = "${channel.name} 的可用线路均未成功，请检查播放源或路由设置", attempt = null)
            return
        }
        val stream = channel.streams.first { it.id == route.streamId }
        val official = MyTvSuperSource.recognizes(stream.url)
        log?.event("INFO", "ROUTE", "channel=${channel.name} stream=${stream.id} route=${route.target.type} profile=${route.target.profile} proxy=${pool.label(route.target)} rule=${route.matchedRule} official=$official")
        _status.value = _status.value.copy(attempt = route, lastAttempt = route, successAt = null,
            message = if (official) "正在获取官方访客会话" else "正在连接 ${channel.name}")
        val source = if (official) try {
            MyTvSuperSource.resolve(withContext(Dispatchers.IO) { pool.client(route.target) }, log)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            log?.error("SOURCE", e)
            if (token != generation) return
            val reason = if (e is IOException) e.message ?: "官方源连接失败" else "官方响应格式不兼容"
            log?.event("ERROR", "SOURCE", reason)
            _status.value = _status.value.copy(error = reason)
            repository.recordFailure(channel.id, route, reason)
            if (token == generation) attempt(channel, resolver, session?.fail(PlaybackFailure(FailureKind.NETWORK)), token)
            return
        } else null
        if (token != generation) return
        val mediaRequests = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val mediaBytes = AtomicLong(0)
        // Official session, media and DRM must use the same concrete route, without domain-rule overrides.
        val selectedClient = if (official) withContext(Dispatchers.IO) { pool.client(route.target) } else null
        val mediaCalls: okhttp3.Call.Factory = if (selectedClient != null) selectedClient.newBuilder()
            .followRedirects(true).followSslRedirects(false).build()
            else RoutedCallFactory(pool, resolver, StreamContext(channel, stream), route.target)
        if (token != generation) return
        val factory = OkHttpDataSource.Factory(mediaCalls)
            .setTransferListener(object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    if (isNetwork && dataSpec.uri.toString() in mediaRequests) mediaBytes.addAndGet(bytesTransferred.toLong())
                }
            })
        val mediaSourceFactory = DefaultMediaSourceFactory(factory)
        if (source != null) {
            val drmProvider = DefaultDrmSessionManagerProvider()
            // Media3 can retry redirected license POSTs itself; constrain all token-bearing requests.
            drmProvider.setDrmHttpDataSourceFactory(OkHttpDataSource.Factory(MyTvSuperSource.licenseClient(requireNotNull(selectedClient), log)))
            mediaSourceFactory.setDrmSessionManagerProvider(drmProvider)
        }
        val instance = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build()
        _player.value = instance
        _status.value = _status.value.copy(channelId = channel.id, message = "正在连接 ${channel.name}", attempt = route, successAt = null)
        log?.event("INFO", "PLAYER", "ExoPlayer created; DASH/DRM=${source != null}")
        var lastLoadType = C.DATA_TYPE_UNKNOWN
        var loadErrorAt = 0L
        var failing = false
        fun fail(failure: PlaybackFailure, drmCode: Int? = null) {
            if (token != generation || instance !== _player.value || failing) return
            failing = true
            val reason = drmCode?.let { "官方 DRM 授权失败（$it），请确认设备支持 Widevine；网页会话可能不兼容 Android" }
                ?: (failure.kind.name + (failure.httpStatus?.let { " HTTP $it" } ?: ""))
            log?.event("ERROR", "FAILOVER", reason)
            _status.value = _status.value.copy(message = "连接暂不可用，正在尝试其他线路", error = reason)
            preparation = scope.launch {
                if (token != generation) return@launch
                repository.recordFailure(channel.id, route, reason)
                if (token == generation) {
                    val refresh = official && failure.httpStatus in listOf(401, 403) && renewed.add(route)
                    log?.event("INFO", "FAILOVER", "refreshSession=$refresh")
                    attempt(channel, resolver, if (refresh) route else session?.fail(failure), token)
                }
            }
        }
        instance.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) mediaRequests.add(loadEventInfo.dataSpec.uri.toString())
                if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) log?.event("INFO", "MANIFEST", "start host=${loadEventInfo.uri.host}")
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                mediaRequests.remove(loadEventInfo.dataSpec.uri.toString())
                if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) log?.event("INFO", "MANIFEST", "loaded bytes=${loadEventInfo.bytesLoaded} elapsedMs=${loadEventInfo.loadDurationMs}")
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                if (!wasCanceled) {
                    val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    log?.event("WARN", "MEDIA", "loadType=${mediaLoadData.dataType} host=${loadEventInfo.uri.host} http=$http exception=${error.javaClass.simpleName}")
                    lastLoadType = mediaLoadData.dataType; loadErrorAt = SystemClock.elapsedRealtime() }
            }
        })
        instance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { log?.event("INFO", "PLAYER", "state=$playbackState (1=idle 2=buffering 3=ready 4=ended)") }
            override fun onIsPlayingChanged(isPlaying: Boolean) { log?.event("INFO", "PLAYER", "isPlaying=$isPlaying") }
            override fun onPlayerError(error: PlaybackException) {
                log?.event("ERROR", "PLAYER", "code=${error.errorCode} name=${error.errorCodeName}")
                log?.error("PLAYER", error)
                val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val kind = when {
                    error.errorCode in 4000..4999 -> FailureKind.DECODER
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> FailureKind.MANIFEST
                    http != null -> FailureKind.HTTP
                    lastLoadType == C.DATA_TYPE_MEDIA && SystemClock.elapsedRealtime() - loadErrorAt < 30000 -> FailureKind.SEGMENT
                    else -> FailureKind.NETWORK
                }
                fail(PlaybackFailure(kind, http), error.errorCode.takeIf { it in 6000..6999 })
            }
        })
        val item = if (source == null) MediaItem.fromUri(route.streamUrl) else MediaItem.Builder()
            .setUri(source.url).setMimeType(MimeTypes.APPLICATION_MPD)
            .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(MyTvSuperSource.LICENSE)
                .setLicenseRequestHeaders(mapOf("X-User-Token" to source.userToken,
                    "X-Client-Platform" to "android", "X-Service-Id" to "super",
                    "Origin" to "https://www.mytvsuper.com", "Referer" to MyTvSuperSource.PAGE))
                .build()).build()
        instance.setMediaItem(item)
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
                    log?.event("INFO", "PLAY", "confirmed media bytes and progress channel=${channel.name}")
                    repository.recordSuccess(channel.id, route)
                    if (token == generation && instance === _player.value) {
                        _status.value = _status.value.copy(message = "正在播放 ${channel.name}", error = null, successAt = System.currentTimeMillis())
                    }
                }
                if (instance.playWhenReady && now - waitingSince >= 20000) {
                    log?.event("WARN", "PLAYER", "no progress for 20 seconds")
                    fail(PlaybackFailure(if (lastLoadType == C.DATA_TYPE_MEDIA) FailureKind.SEGMENT else FailureKind.NETWORK))
                }
            }
        }
    }
    fun stop(showMessage: Boolean = true) {
        log?.event("INFO", "PLAY", "stop showMessage=$showMessage")
        generation++
        preparation?.cancel(); watcher?.cancel(); session?.stop(); session = null
        _player.value?.release(); _player.value = null
        if (showMessage) _status.value = _status.value.copy(message = "已停止播放", attempt = null, lastAttempt = null, error = null)
    }
}
