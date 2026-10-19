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
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import com.tvatlas.player.source.ViuTvSource
import com.tvatlas.player.source.SourceResolutionException
import com.tvatlas.player.source.MyTvSuperSource
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
    val loading: Boolean = false, val selectedStreamId: String? = null,
    val attemptNumber: Int = 0, val candidateCount: Int = 0,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCoordinator(
    private val context: Context, private val repository: PlayerRepository,
    private val pool: ProxyClientPool, private val scope: CoroutineScope,
    private val log: com.tvatlas.player.debug.DebugLog? = null,
    private val onNodeFailure: (RouteTarget) -> Unit = {},
    private val subtitlesEnabled: () -> Boolean = { false },
    private val allowStart: (Channel, String?) -> Boolean = { _, _ -> true },
) {
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player = _player.asStateFlow()
    private val _speed = MutableStateFlow(0L)
    val speed = _speed.asStateFlow()
    private val _networkTarget = MutableStateFlow<RouteTarget?>(null)
    val networkTarget = _networkTarget.asStateFlow()
    private val _status = MutableStateFlow(PlaybackStatus())
    val status = _status.asStateFlow()
    private val observationSession = java.util.UUID.randomUUID().toString()
    private val reliability = RouteReliabilityStore(java.io.File(context.noBackupFilesDir, "playback/route-observations.json"))
    private val bundledHlsUrls by lazy {
        context.assets.open("regional-live.m3u").bufferedReader().use { reader ->
            com.tvatlas.core.playlist.M3uParser.parse(reader.readText(), "formats")
                .flatMap { it.streams }.map { it.url }.toSet()
        }
    }
    val routeRecords = reliability.state
    private suspend fun observe(channelId: String, route: RouteAttempt, success: Boolean, failure: FailureKind? = null) {
        try { reliability.record(RouteObservation(channelId, route.streamId, route.target, System.currentTimeMillis(), success, failure, "$observationSession:$generation:${session?.attemptCount}")) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { log?.event("WARN", "ROUTE_HISTORY", "local history unavailable; playback continues") }
    }
    private var savedRoute: Pair<String, RouteTarget>? = null
    private var session: FailoverSession? = null
    private var watcher: Job? = null
    private var preparation: Job? = null
    private var generation = 0L
    private var drmActive: java.util.concurrent.atomic.AtomicBoolean? = null
    private val renewed = mutableSetOf<RouteAttempt>()
    private val requestFailures = mutableMapOf<String, com.tvatlas.player.proxy.RequestRouteFailures>()

    fun preparing(channel: Channel, streamId: String? = null) {
        _speed.value = 0L
        _status.value = PlaybackStatus(channel.id, "正在准备播放线路…", loading = true, selectedStreamId = streamId)
    }

    fun preparationFailed(channel: Channel, streamId: String? = null) {
        _status.value = PlaybackStatus(channel.id, "无法准备播放", error = "节点准备失败，请检查代理配置后重试", selectedStreamId = streamId)
    }
    fun play(channel: Channel, resolver: DefaultRouteResolver, streamId: String? = null) {
        stop(false)
        renewed.clear()
        requestFailures.clear()
        val token = generation
        _status.value = PlaybackStatus(channel.id, "正在连接 ${channel.name}", loading = true, selectedStreamId = streamId)
        preparation = scope.launch {
            val history = repository.preferred(channel.id)
            if (token != generation) return@launch
            val officialIds = channel.streams.filter {
                MyTvSuperSource.recognizes(it.url) || ViuTvSource.recognizes(it.url)
            }.map { it.id }.toSet()
            val orderedChannel = channel.copy(streams = channel.streams.sortedBy { if (it.id in officialIds) 0 else 1 })
            val preferred = history?.takeIf { streamId != null || officialIds.isEmpty() || it.streamId in officialIds }
            savedRoute = preferred?.let { it.streamId to it.target }
            val basePlan = resolver.channelPlan(orderedChannel, preferred, streamId)
            val observations = try { reliability.load() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            if (token != generation) return@launch
            val plan = RouteReliability.order(basePlan, observations, channel.id, System.currentTimeMillis())
            if (plan.attempts.isEmpty()) _status.value = _status.value.copy(error = "该地区没有启用的匹配节点，请导入对应地区节点或手动选择线路")
            val newSession = FailoverSession(plan)
            session = newSession
            attempt(channel, resolver, newSession.start(), token)
        }
    }

    private suspend fun attempt(channel: Channel, resolver: DefaultRouteResolver, route: RouteAttempt?, token: Long) {
        if (token != generation) return
        watcher?.cancel()
        drmActive?.set(false); drmActive = null
        _player.value?.release(); _player.value = null
        _speed.value = 0L
        if (route == null) {
            log?.event("ERROR", "PLAY", "all routes exhausted channel=${channel.name} detail=${_status.value.error}")
            _status.value = _status.value.copy(message = "${channel.name} 的可用线路均未成功，请检查播放源或路由设置", attempt = null, loading = false)
            return
        }
        _networkTarget.value = route.target
        val stream = channel.streams.first { it.id == route.streamId }
        val viu = com.tvatlas.player.source.ViuTvSource.recognizes(stream.url)
        val official = MyTvSuperSource.recognizes(stream.url) || viu
        log?.event("INFO", "ROUTE", "channel=${channel.name} stream=${stream.id} route=${route.target.type} profile=${route.target.profile} proxy=${pool.label(route.target)} rule=${route.matchedRule} official=$official")
        _status.value = _status.value.copy(attempt = route, lastAttempt = route, successAt = null, loading = true,
            attemptNumber = session?.attemptCount ?: 0, candidateCount = session?.candidateCount ?: 0,
            message = when {
                (session?.attemptCount ?: 0) > 1 -> "连接失败，正在尝试备用线路"
                savedRoute == (route.streamId to route.target) -> "正在使用上次成功线路"
                else -> "正在连接可用线路"
            } + if (official) " · 获取官方会话" else "")
        val source = if (official) try {
            if (viu) com.tvatlas.player.source.ViuTvSource.resolve(withContext(Dispatchers.IO) { pool.client(route.target) }, log, stream.url)
            else MyTvSuperSource.resolve(withContext(Dispatchers.IO) { pool.client(route.target) }, log, stream.url)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            log?.error("SOURCE", e)
            if (token != generation) return
            val reason = if (e is IOException) e.message ?: "官方源连接失败" else "官方响应格式不兼容"
            log?.event("ERROR", "SOURCE", reason)
            _status.value = _status.value.copy(error = reason)
            val failure = (e as? SourceResolutionException)?.failure ?: PlaybackFailure(
                if (e is IOException) FailureKind.NETWORK else FailureKind.MANIFEST)
            if (failure.kind == FailureKind.NETWORK) onNodeFailure(route.target)
            observe(channel.id, route, false, failure.kind)
            repository.recordFailure(channel.id, route, reason)
            if (token == generation) attempt(channel, resolver, session?.fail(failure), token)
            return
        } else null
        if (token != generation) return
        if (!allowStart(channel, _status.value.selectedStreamId)) {
            stop(false)
            _status.value = _status.value.copy(message = "请确认网络设置后播放", loading = false, attempt = null)
            return
        }
        val mediaRequests = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val mediaBytes = AtomicLong(0)
        val downloadBytes = AtomicLong(0)
        val observedTarget = java.util.concurrent.atomic.AtomicReference(route.target)
        _speed.value = 0L
        // Official session, media and DRM must use the same concrete route, without domain-rule overrides.
        val selectedClient = if (official) withContext(Dispatchers.IO) { pool.client(route.target) } else null
        val mediaCalls: okhttp3.Call.Factory = if (selectedClient != null) selectedClient.newBuilder()
            .followRedirects(true).followSslRedirects(false).build()
            else RoutedCallFactory(pool, resolver, StreamContext(channel, stream), route.target,
                requestFailures.getOrPut(stream.id) { com.tvatlas.player.proxy.RequestRouteFailures() }, onNodeFailure, { target, url ->
                    if (url in mediaRequests || mediaBytes.get() == 0L) observedTarget.set(target)
                })
        if (token != generation) return
        val factory = OkHttpDataSource.Factory(mediaCalls)
            .setDefaultRequestProperties(if (official) emptyMap() else stream.headers)
            .setTransferListener(object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    if (isNetwork) {
                        downloadBytes.addAndGet(bytesTransferred.toLong())
                        if (dataSpec.uri.toString() in mediaRequests) mediaBytes.addAndGet(bytesTransferred.toLong())
                    }
                }
            })
        val mediaSourceFactory = DefaultMediaSourceFactory(factory)
        if (source != null && source.drmRequired) {
            // Media3 can retry redirected license POSTs itself; constrain all token-bearing requests.
            val licenseNetwork = requireNotNull(selectedClient).newBuilder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS).readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val callback = HttpMediaDrmCallback(source.licenseUrl, true,
                OkHttpDataSource.Factory(if (viu) ViuTvSource.licenseClient(licenseNetwork, source.userToken, log)
                    else MyTvSuperSource.licenseClient(licenseNetwork, log)))
            if (!viu) MyTvSuperSource.licenseHeaders(source.userToken).forEach { (key, value) -> callback.setKeyRequestProperty(key, value) }
            // This source uses a web guest entitlement. The official web player requests
            // SW_SECURE_CRYPTO; select the corresponding Widevine software implementation.
            val provisioningTargets = resolver.provisioningTargets(StreamContext(channel, stream), route.target)
            val active = java.util.concurrent.atomic.AtomicBoolean(true).also { drmActive = it }
            val fallbackCallback = FallbackDrmCallback(callback, provisioningTargets, { target ->
                val client = pool.client(target).newBuilder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                // No TVB/ViuTV key-request headers are attached to Google device provisioning.
                HttpMediaDrmCallback("", false, OkHttpDataSource.Factory(MyTvSuperSource.licenseClient(client, log)))
            }, log, active::get)
            val manager = DefaultDrmSessionManager.Builder()
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                    val drm = FrameworkMediaDrm.DEFAULT_PROVIDER.acquireExoMediaDrm(uuid)
                    if (drm is FrameworkMediaDrm) {
                        try { drm.setPropertyString("securityLevel", "L3") }
                        catch (error: RuntimeException) { log?.error("DRM_CONFIG", error) }
                        val level = runCatching { drm.getPropertyString("securityLevel") }.getOrDefault("unknown")
                        log?.event("INFO", "DRM_CONFIG", "platform=web_android securityLevel=${level.takeIf { it in listOf("L1", "L2", "L3") } ?: "unknown"}")
                    } else log?.event("ERROR", "DRM_CONFIG", "Widevine unavailable")
                    drm
                }.build(fallbackCallback)
            mediaSourceFactory.setDrmSessionManagerProvider { manager }
        }
        val instance = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build()
        instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled())
            .setSelectUndeterminedTextLanguage(true).build()
        _player.value = instance
        _status.value = _status.value.copy(channelId = channel.id, message = _status.value.message + " · 正在缓冲", attempt = route, successAt = null, loading = false)
        log?.event("INFO", "PLAYER", "ExoPlayer created; DASH/DRM=${source != null}")
        var lastLoadType = C.DATA_TYPE_UNKNOWN
        var loadErrorAt = 0L
        var failing = false
        fun fail(failure: PlaybackFailure, drmCode: Int? = null) {
            if (token != generation || instance !== _player.value || failing) return
            failing = true
            if (failure.kind == FailureKind.NETWORK) onNodeFailure(route.target)
            val reason = drmCode?.let {
                when {
                    failure.kind == FailureKind.NETWORK && it == PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> "设备初始化网络连接失败（$it）"
                    failure.kind == FailureKind.NETWORK -> "DRM 授权网络连接失败（$it）"
                    failure.httpStatus != null -> "官方 DRM 授权请求失败（HTTP ${failure.httpStatus}，播放器代码 $it）"
                    it == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "设备不支持所需的 Widevine DRM（$it）"
                    else -> "DRM 会话失败（$it），请查看调试日志"
                }
            }
                ?: when (failure.kind) {
                    FailureKind.NETWORK -> "播放源网络连接失败"
                    FailureKind.HTTP -> "播放源请求失败（HTTP ${failure.httpStatus}）"
                    FailureKind.SEGMENT -> "视频分片下载失败"
                    FailureKind.MANIFEST -> "播放源格式无效或不受支持"
                    FailureKind.DECODER -> "设备无法解码当前播放源"
                    FailureKind.DRM -> "DRM 授权或设备初始化失败"
                }
            log?.event("ERROR", "FAILOVER", reason)
            _status.value = _status.value.copy(message = "连接暂不可用，正在尝试其他线路", error = reason)
            preparation = scope.launch {
                if (token != generation) return@launch
                observe(channel.id, route, false, failure.kind)
                repository.recordFailure(channel.id, route, reason)
                if (token == generation) {
                    val refresh = official && failure.httpStatus in listOf(401, 403) && renewed.add(route)
                    log?.event("INFO", "FAILOVER", "refreshSession=$refresh kind=${failure.kind} drmCode=$drmCode")
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
                    log?.error("MEDIA", error)
                    lastLoadType = mediaLoadData.dataType; loadErrorAt = SystemClock.elapsedRealtime() }
            }
        })
        instance.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                log?.event("INFO", "SUBTITLE", "groups=${textGroups.size} selected=${textGroups.count { it.isSelected }} enabled=${subtitlesEnabled()}")
                val parameters = instance.trackSelectionParameters
                if (!subtitlesEnabled() || C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes ||
                    parameters.overrides.values.any { it.type == C.TRACK_TYPE_TEXT }) return
                val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (groups.any { it.isSelected }) return
                val group = groups.firstOrNull { g -> (0 until g.length).any { g.isTrackSupported(it) } } ?: return
                val index = (0 until group.length).first { group.isTrackSupported(it) }
                instance.trackSelectionParameters = parameters.buildUpon()
                    .addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
            }
            override fun onPlaybackStateChanged(playbackState: Int) { log?.event("INFO", "PLAYER", "state=$playbackState (1=idle 2=buffering 3=ready 4=ended)") }
            override fun onIsPlayingChanged(isPlaying: Boolean) { log?.event("INFO", "PLAYER", "isPlaying=$isPlaying") }
            override fun onPlayerError(error: PlaybackException) {
                log?.event("ERROR", "PLAYER", "code=${error.errorCode} name=${error.errorCodeName}")
                log?.error("PLAYER", error)
                val failure = PlaybackErrorClassifier.classify(error,
                    lastLoadType == C.DATA_TYPE_MEDIA && SystemClock.elapsedRealtime() - loadErrorAt < 30000)
                fail(failure, error.errorCode.takeIf { it in 6000..6999 })
            }
        })
        // A few verified HLS endpoints have no .m3u8 suffix. URI inference would
        // otherwise select a progressive extractor and reject the playlist text.
        val bundledHls = source == null && withContext(Dispatchers.IO) { route.streamUrl in bundledHlsUrls }
        val item = if (source == null) MediaItem.Builder().setUri(route.streamUrl).apply {
            if (bundledHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build() else MediaItem.Builder()
            .setUri(source.url).setMimeType(source.mimeType).apply {
                if (source.drmRequired) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(source.licenseUrl)
                    .setLicenseRequestHeaders(if (viu) emptyMap() else MyTvSuperSource.licenseHeaders(source.userToken))
                    .build())
            }.build()
        val nativeHls = source == null && (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
            android.net.Uri.parse(route.streamUrl).path.orEmpty().endsWith(".m3u8", true))
        if (nativeHls) instance.setMediaSource(androidx.media3.exoplayer.hls.HlsMediaSource.Factory(factory)
            .setAllowChunklessPreparation(false).createMediaSource(item))
        else instance.setMediaItem(item)
        instance.prepare(); instance.playWhenReady = true
        watcher = scope.launch {
            val gate = SuccessGate()
            var waitingSince = SystemClock.elapsedRealtime()
            var previousPosition = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            var sampleBytes = 0L
            while (isActive && token == generation && instance === _player.value && !failing) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                _networkTarget.value = observedTarget.get()
                if (now - sampleAt >= 1000) {
                    val bytes = downloadBytes.get(); _speed.value = ((bytes - sampleBytes).coerceAtLeast(0) * 1000 / (now - sampleAt))
                    sampleBytes = bytes; sampleAt = now
                }
                val progressing = instance.isPlaying && instance.currentPosition != previousPosition
                previousPosition = instance.currentPosition
                if (progressing || !instance.playWhenReady) waitingSince = now
                if (gate.update(now, progressing, mediaBytes.get())) {
                    log?.event("INFO", "PLAY", "confirmed media bytes and progress channel=${channel.name}")
                    observe(channel.id, route, true)
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
        _networkTarget.value = null
        _speed.value = 0L
        drmActive?.set(false); drmActive = null
        preparation?.cancel(); watcher?.cancel(); session?.stop(); session = null
        _player.value?.release(); _player.value = null
        _speed.value = 0L
        if (showMessage) _status.value = _status.value.copy(message = "已停止播放", attempt = null, error = null, loading = false, successAt = null)
    }
}
