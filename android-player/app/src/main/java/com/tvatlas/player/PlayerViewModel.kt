package com.tvatlas.player

import android.app.Application
import android.net.Uri
import android.content.Intent
import com.tvatlas.player.update.*
import com.tvatlas.player.network.readCancellable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvatlas.core.model.*
import com.tvatlas.core.vod.*
import com.tvatlas.core.routing.*
import com.tvatlas.player.playback.PlaybackCoordinator
import com.tvatlas.player.proxy.ProxyClientPool
import com.tvatlas.player.proxy.MihomoRuntime
import com.tvatlas.player.proxy.CoreInfo
import com.tvatlas.player.proxy.NodeLatencyTester
import com.tvatlas.core.subscription.SubscriptionException
import com.tvatlas.player.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

data class StartupState(val localReady: Boolean = false, val completed: Int = 0, val total: Int = 0,
    val running: Boolean = true, val failed: Boolean = false)

data class SubscriptionFeedback(val id: String?, val message: String, val error: Boolean = false)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val debugLog = (application as? AtlasApplication)?.debugLog ?: com.tvatlas.player.debug.DebugLog(
        java.io.File(application.noBackupFilesDir, "debug/player.log"), "TVAtlas ${BuildConfig.VERSION_NAME}")
    private val constructionStarted = android.os.SystemClock.elapsedRealtime().also { debugLog.event("INFO", "STARTUP", "view-model begin") }
    private fun <T> startupStep(name: String, create: () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        debugLog.event("INFO", "STARTUP_COMPONENT", "$name begin")
        try { return create() }
        catch (error: Exception) { debugLog.error("STARTUP_COMPONENT", error); throw error }
        finally { debugLog.event("INFO", "STARTUP_COMPONENT", "$name elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}") }
    }
    private val database = startupStep("database") { PlayerDatabase.open(application) }
    private val vault = startupStep("credential-vault") { CredentialVault(application) }
    private val core = startupStep("proxy-runtime") { MihomoRuntime(application, vault) }
    val traffic = startupStep("traffic-store") { com.tvatlas.player.traffic.TrafficStore(java.io.File(application.noBackupFilesDir, "traffic/usage.json")) }
    private val pool = startupStep("network-clients") { ProxyClientPool(vault, core, traffic) }
    private val playlistClient = pool.direct.newBuilder().followRedirects(true).followSslRedirects(true)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private var appForeground = true
    private var playbackSeries: UnifiedMovie? = null
    private var playbackEpisode: String? = null
    private var latestSeries: UnifiedMovie? = null
    private val _nextVodEpisode = MutableStateFlow<String?>(null)
    val nextVodEpisode = _nextVodEpisode.asStateFlow()
    private val vodQuality by lazy { com.tvatlas.player.vod.VodQualityCache(java.io.File(getApplication<Application>().noBackupFilesDir, "vod-quality.json")) }
    private fun qualityContext(label: String) = "$label|${connectivity.activeNetwork}"
    private fun clearVodQueue() { playbackSeries = null; playbackEpisode = null; _nextVodEpisode.value = null }
    private var vodPageActive = false
    fun setVodPageActive(active: Boolean) {
        vodPageActive = active
        activeUnifiedVod?.setVisible(active && appForeground)
    }
    fun setAppForeground(active: Boolean) {
        appForeground = active
        activeUnifiedVod?.setVisible(active && vodPageActive)
        if (!active) cancelVodSelection()
        else if (vodPageActive) activeUnifiedVod?.refreshDue()
    }
    private var vodActive: com.tvatlas.player.vod.VodPlaybackController? = null
    val vod by lazy {
        com.tvatlas.player.vod.VodPlaybackController(getApplication(), viewModelScope, pool.direct,
            { com.tvatlas.player.vod.VodProgressStore(getApplication()) },
            { appForeground && allowCurrentNetwork(options.value) && !needsConsent() }, debugLog,
            onEnded = { if (options.value.vodAutoNext) advanceVodEpisode() },
            onClosed = { cancelVodSelection(); clearVodQueue() },
            onMeasuredQuality = { item, height, label -> val context = qualityContext(label)
                viewModelScope.launch { vodQuality.record(item.url, context, height, 0, actual = true) } },
            onMediaFailure = { item, label, code -> val context = qualityContext(label)
                viewModelScope.launch {
                    if (code in 4001..4005) vodQuality.reject(item.url, context) else vodQuality.invalidate(item.url, context)
                } }).also { vodActive = it }
    }
    fun openVod(url: String, title: String) {
        cancelVodSelection(); clearVodQueue()
        val item = try { com.tvatlas.core.vod.VodItem.direct(url, title) }
            catch (_: IllegalArgumentException) { _message.value = "请输入有效的视频直链"; return }
        cloudNetworkAction {
            viewModelScope.launch {
                try {
                    autoRefreshJob?.cancelAndJoin()
                    stopPlayback(false)
                    val (client, label) = prepareVodClient()
                    vod.open(item, client, label)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { debugLog.error("VOD_ROUTE", error); _message.value = "点播网络不可用，请重新选择节点" }
            }
        }
    }
    private var vodChoiceJob: Job? = null
    private var vodChoiceGeneration = 0L
    private val _vodSelecting = MutableStateFlow(false)
    val vodSelecting = _vodSelecting.asStateFlow()
    fun cancelVodSelection() { vodChoiceGeneration++; vodChoiceJob?.cancel(); _vodSelecting.value = false }
    fun openVodChoice(choice: com.tvatlas.core.vod.VodEpisodeChoice, title: String) {
        cancelVodSelection(); clearVodQueue()
        val token = vodChoiceGeneration
        cloudNetworkAction {
            if (token != vodChoiceGeneration) return@cloudNetworkAction
            vodChoiceJob = viewModelScope.launch {
                _vodSelecting.value = true
                try {
                    autoRefreshJob?.cancelAndJoin()
                    val (client, label) = prepareVodClient()
                    val ordered = com.tvatlas.player.vod.VodLineSelector(
                        com.tvatlas.player.vod.vodMediaClient(client, { canLoadChannelArtwork() }, debugLog)).order(choice.candidates)
                    val items = ordered.map { com.tvatlas.core.vod.VodItem.direct(it.url, title.take(80)) }
                    if (items.isEmpty()) { _message.value = "这一集没有可播放地址"; return@launch }
                    stopPlayback(false)
                    vod.open(items.first(), client, label, items.drop(1))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { debugLog.error("VOD_SELECT", error); _message.value = "自动选线失败，请重试或检查网络" }
                finally { if (token == vodChoiceGeneration) _vodSelecting.value = false }
            }
        }
    }
    private var activeUnifiedVod: com.tvatlas.player.vod.UnifiedVodRepository? = null
    private var activeVodSubscriptions: com.tvatlas.player.vod.VodSubscriptionStore? = null
    internal val vodSubscriptions by lazy {
        val bootstrap = kotlinx.serialization.json.Json.decodeFromString<List<com.tvatlas.core.vod.SubscribedVodSource>>(
            getApplication<Application>().assets.open("vod-test-sources.json").bufferedReader().use { it.readText() })
        com.tvatlas.player.vod.VodSubscriptionStore(java.io.File(getApplication<Application>().noBackupFilesDir, "vod-subscriptions"), bootstrap, viewModelScope,
            feeds = kotlinx.serialization.json.Json.parseToJsonElement(getApplication<Application>().assets.open("vod-subscriptions.json").bufferedReader().use { it.readText() })
                .let { root -> ((root as kotlinx.serialization.json.JsonObject).getValue("feeds") as kotlinx.serialization.json.JsonArray).associate { item ->
                    val row = item as kotlinx.serialization.json.JsonObject
                    (row.getValue("name") as kotlinx.serialization.json.JsonPrimitive).content to (row.getValue("url") as kotlinx.serialization.json.JsonPrimitive).content
                } },
            fetch = { url -> resourceBytes(url, 2 * 1024 * 1024, "VOD_SUBSCRIPTION").toString(Charsets.UTF_8) },
            validate = { api ->
                requireNetwork(settings.options.first()); require(!needsConsent())
                val client = com.tvatlas.player.vod.VodCatalogClient(prepareVodClient(vodSourceRoutes.get(api)).first, debugLog, eventTag = "VOD_VALIDATE")
                client.load(api, 1, "").movies.any { it.episodes.isNotEmpty() }
            }, applyLive = { lists, removed -> repository.syncUnifiedLive(lists, removed); epg.invalidateLibrary() }).also { store ->
            activeVodSubscriptions = store
            viewModelScope.launch { store.state.map { it.sources.map { source -> source.api } }.distinctUntilChanged()
                .collect { activeUnifiedVod?.updateSources(it) } }
        }
    }
    fun addMediaSubscription(name: String, url: String) = cloudNetworkAction {
        action("统一订阅已保存，请查看同步结果") { vodSubscriptions.addFeed(name, url) }
    }
    fun removeMediaSubscription(url: String) = action("订阅已移除，其他订阅及手动来源已保留") { vodSubscriptions.removeFeed(url) }
    fun syncVodSubscriptions(manual: Boolean = false) {
        if (!manual && (!options.value.autoRefresh || isMetered() || playback.player.value != null || vodActive?.state?.value?.player != null)) return
        cloudNetworkAction { vodSubscriptions.sync(force = manual, interval = options.value.refreshHours * 3600000L) }
    }
    internal val vodHealth by lazy { com.tvatlas.player.vod.VodSourceHealth(
        java.io.File(getApplication<Application>().noBackupFilesDir, "vod-health.json"), viewModelScope) }
    private fun vodHealthContext(source: String) = "${vodSourceRoutes.get(source)}|${connectivity.activeNetwork}"
    private fun mayRecordVodHealth(success: Boolean = false): Boolean {
        val network = connectivity.activeNetwork ?: return false
        if (!allowCurrentNetwork(options.value) || needsConsent()) return false
        return success || connectivity.getNetworkCapabilities(network)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    private val _recheckingVod = MutableStateFlow<Set<String>>(emptySet())
    val recheckingVod = _recheckingVod.asStateFlow()
    fun vodHealthLabel(source: String): String {
        if (source in _recheckingVod.value) return "正在检查"
        val health = vodHealth.get(source, vodHealthContext(source)) ?: return "尚未检测"
        return if (health.retryAt > System.currentTimeMillis()) "暂缓访问，可重新检查"
            else if (health.failures > 0) "最近连接未成功" else "最近可用"
    }
    fun recheckVodSource(source: String) = cloudNetworkAction {
        if (source !in _recheckingVod.value) viewModelScope.launch {
            _recheckingVod.update { it + source }
            val context = vodHealthContext(source)
            try {
                vodHealth.reset(source, context)
                withTimeout(8000) { com.tvatlas.player.vod.VodCatalogClient(prepareVodClient(vodSourceRoutes.get(source)).first, debugLog).load(source, 1, "") }
                vodHealth.record(source, context, true)
                _message.value = "来源可用，已恢复参与片库加载"
            } catch (timeout: TimeoutCancellationException) {
                if (mayRecordVodHealth()) vodHealth.record(source, context, false)
                _message.value = "来源响应超时，可检查网络后重试"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mayRecordVodHealth()) vodHealth.record(source, context, false)
                _message.value = "来源暂不可用，可调整网络后重试"
            } finally { _recheckingVod.update { it - source } }
        }
    }
    internal val unifiedVod by lazy {
        com.tvatlas.player.vod.UnifiedVodRepository(vodSubscriptions.state.value.sources.map { it.api },
            java.io.File(getApplication<Application>().noBackupFilesDir, "vod-catalog"), viewModelScope, debugLog,
            health = vodHealth, healthContext = ::vodHealthContext, canRecordHealth = ::mayRecordVodHealth) { source ->
            requireNetwork(settings.options.first()); require(!needsConsent())
            val (client, _) = prepareVodClient(vodSourceRoutes.get(source))
            com.tvatlas.player.vod.VodCatalogClient(client, debugLog)
        }.also { activeUnifiedVod = it; it.setVisible(vodPageActive && appForeground) }
    }
    private var unifiedEpisodeSources = emptyMap<String, String>()
    suspend fun unifiedDetails(movie: com.tvatlas.core.vod.UnifiedMovie): com.tvatlas.core.vod.UnifiedMovie {
        val result = unifiedVod.details(movie)
        latestSeries = result
        unifiedEpisodeSources = result.offers.flatMap { offer -> offer.movie.episodes.map { it.url to offer.source } }.asReversed().toMap()
        return result
    }
    fun openUnifiedChoice(choice: com.tvatlas.core.vod.VodEpisodeChoice, identity: String, title: String) {
        cancelVodSelection(); val token = vodChoiceGeneration
        val series = latestSeries?.takeIf { identity == it.id + ":" + VodEpisodeChoices.identity(choice.name) }
        cloudNetworkAction {
            if (token != vodChoiceGeneration) return@cloudNetworkAction
            vodChoiceJob = viewModelScope.launch {
                _vodSelecting.value = true
                try {
                    autoRefreshJob?.cancelAndJoin()
                    val prepared = choice.candidates.mapNotNull { unifiedEpisodeSources[it.url] }.distinct().associateWith { source ->
                        prepareVodClient(vodSourceRoutes.get(source))
                    }
                    val fallback = prepared.values.firstOrNull() ?: throw IllegalArgumentException("请重新打开影片获取播放信息")
                    val mediaClients = prepared.mapValues { (_, value) -> com.tvatlas.player.vod.vodMediaClient(value.first, { canLoadChannelArtwork() }, debugLog) }
                    val ordered = com.tvatlas.player.vod.VodLineSelector(fallback.first, cache = vodQuality,
                        context = { episode -> qualityContext(prepared[unifiedEpisodeSources[episode.url]]?.second ?: fallback.second) }) { episode ->
                        mediaClients[unifiedEpisodeSources[episode.url]] ?: fallback.first
                    }.order(choice.candidates)
                    val items = ordered.map { com.tvatlas.core.vod.VodItem.episode(it.url, title.take(80), identity) }
                    if (items.isEmpty()) throw IllegalArgumentException("暂无可用地址")
                    val clients = items.associate { it.url to (prepared[unifiedEpisodeSources[it.url]]?.first ?: fallback.first) }
                    val labels = items.associate { it.url to (prepared[unifiedEpisodeSources[it.url]]?.second ?: fallback.second) }
                    ensureActive()
                    if (!appForeground || token != vodChoiceGeneration) return@launch
                    playbackSeries = series; playbackEpisode = choice.name
                    _nextVodEpisode.value = series?.let { VodEpisodeChoices.next(VodEpisodeChoices.of(it.movie), choice.name)?.name }
                    stopPlayback(false)
                    vod.open(items.first(), fallback.first, fallback.second, items.drop(1),
                        alternativeClients = clients, alternativeLabels = labels)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    debugLog.error("VOD_UNIFIED", error); _message.value = "播放信息暂不可用，请重试或检查网络"
                    vodActive?.notice("这一集暂时无法播放，可返回片库重新获取播放信息。")
                }
                finally { if (token == vodChoiceGeneration) _vodSelecting.value = false }
            }
        }
    }
    fun advanceVodEpisode() {
        val series = playbackSeries ?: return
        val current = playbackEpisode ?: return
        val next = VodEpisodeChoices.next(VodEpisodeChoices.of(series.movie), current) ?: return
        if (_vodSelecting.value || !appForeground) return
        val oldKey = vodActive?.state?.value?.item?.key ?: return
        cloudNetworkAction {
            cancelVodSelection(); val token = vodChoiceGeneration
            vodChoiceJob = viewModelScope.launch {
                _vodSelecting.value = true
                try {
                    val fresh = unifiedDetails(series)
                    ensureActive()
                    if (token != vodChoiceGeneration || !appForeground || vodActive?.state?.value?.item?.key != oldKey) return@launch
                    val chosen = VodEpisodeChoices.of(fresh.movie).singleOrNull { VodEpisodeChoices.identity(it.name) == VodEpisodeChoices.identity(next.name) }
                        ?: throw IllegalArgumentException("下一集暂未更新")
                    vodChoiceJob = null
                    openUnifiedChoice(chosen, fresh.id + ":" + VodEpisodeChoices.identity(chosen.name), fresh.movie.name + " · " + chosen.name)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { vodActive?.notice("下一集暂不可用，可返回片库重试；没有跳过缺失集数。") }
                finally { if (token == vodChoiceGeneration) _vodSelecting.value = false }
            }
        }
    }
    suspend fun loadVodCatalog(api: String, page: Int, search: String, id: String? = null): com.tvatlas.core.vod.CatalogPage {
        requireNetwork(settings.options.first())
        require(!needsConsent()) { "读取片单需要确认移动网络" }
        val (client, label) = prepareVodClient()
        debugLog.event("INFO", "VOD_CATALOG_ROUTE", "route=$label page=$page detail=${id != null} search=${search.isNotBlank()}")
        return com.tvatlas.player.vod.VodCatalogClient(client, debugLog).load(api, page, search, id)
    }
    suspend fun createVodArtworkLoader(): coil.ImageLoader {
        val (client, _) = prepareVodClient()
        val guarded = client.newBuilder().addInterceptor { chain ->
            if (!canLoadChannelArtwork()) throw java.io.IOException("当前网络不允许加载海报")
            chain.proceed(chain.request())
        }.build()
        return coil.Coil.imageLoader(getApplication()).newBuilder().okHttpClient(guarded).build()
    }
    private val _vodRoute = MutableStateFlow(RouteTarget.DIRECT)
    val vodRoute = _vodRoute.asStateFlow()
    private val vodSourceRoutes = com.tvatlas.core.vod.VodSourceRoutes()
    private var selectedVodSource = "direct-video"
    fun selectVodSource(source: String) {
        selectedVodSource = source
        _vodRoute.value = vodSourceRoutes.get(source)
    }
    fun setVodRoute(target: RouteTarget) {
        cancelVodSelection()
        vodSourceRoutes.set(selectedVodSource, target)
        _vodRoute.value = target
    }
    fun isSystemVpnActive(): Boolean = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    private suspend fun prepareVodClient(target: RouteTarget = _vodRoute.value): Pair<okhttp3.OkHttpClient, String> {
        requireNetwork(settings.options.first())
        val profiles = repository.library.first().profiles
        return routeChanges.withLock {
            withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
            val profile = profiles.firstOrNull { it.id == target.profile }
            val subscription = profile?.subscriptionId?.let { database.dao().subscription(it)?.name }
            val label = if (target.type == RouteType.DIRECT) "直连" else listOfNotNull(subscription, profile?.name).joinToString(" · ")
            debugLog.event("INFO", "VOD_NETWORK", "route=$label systemVpn=${isSystemVpnActive()} metered=${isMetered()} fallback=false")
            withContext(Dispatchers.IO) { pool.client(target) } to label
        }
    }
    fun toggleVod() {
        if (vod.state.value.playing || vod.state.value.player?.playWhenReady == true) vod.pause()
        else cloudNetworkAction { vod.toggle() }
    }
    fun restartVod() = cloudNetworkAction { vod.restart() }
    private var cloudActive: com.tvatlas.player.cloud.CloudLibraryController? = null
    val cloudLibrary by lazy {
        com.tvatlas.player.cloud.CloudLibraryController(viewModelScope,
            { com.tvatlas.player.cloud.CloudShareStore(application) },
            com.tvatlas.player.cloud.CloudShareClient(playlistClient)) {
            requireNetwork(options.value)
            require(!needsConsent()) { "读取网盘目录需要移动网络确认，请点击重新读取并确认" }
        }.also { cloudActive = it }
    }
    private val _pendingCloudConsent = MutableStateFlow(false)
    val pendingCloudConsent = _pendingCloudConsent.asStateFlow()
    private var pendingCloudAction: (() -> Unit)? = null
    fun cloudNetworkAction(action: () -> Unit) {
        if (!allowCurrentNetwork(options.value)) { _message.value = networkPolicyMessage(); return }
        if (needsConsent()) { pendingCloudAction = action; _pendingCloudConsent.value = true; return }
        action()
    }
    fun openCloudWebsite(share: com.tvatlas.core.cloud.CloudShare) = cloudNetworkAction {
        try { getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(share.webUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { _message.value = "未找到浏览器，无法打开网盘官方页面" }
    }
    private val repository = PlayerRepository(database, playlistClient, debugLog,
        regionalCatalog = "", regionalCatalogLoader = {
            getApplication<Application>().assets.open("regional-live.m3u").bufferedReader().use { it.readText() }
        }) { url -> resourceBytes(url, 8 * 1024 * 1024, "PLAYLIST").toString(Charsets.UTF_8) }
    private val subscriptionRepository = SubscriptionRepository(database, playlistClient, vault, core, debugLog) { url ->
        requireNetwork(settings.options.first())
        val profiles = library.value.profiles
        val selected = settings.subscriptionRoute.first()
        withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
        SubscriptionDownloader(pool::client, debugLog).downloadWithMetadata(url, subscriptionTargets(selected, profiles))
    }
    val subscriptions = subscriptionRepository.subscriptions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val settings = SettingsStore(application)
    val options = settings.options.stateIn(viewModelScope, SharingStarted.Eagerly, UserOptions())
    val recent = database.dao().recent().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val library = repository.library.stateIn(viewModelScope, SharingStarted.Eagerly, Library(emptyList(), emptyList(), emptyList(), RuleConfig()))
    val ruleFiles = repository.ruleFiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun selectRuleFile(id: String?) = routeAction("规则已切换，重新播放后生效") { repository.selectRuleFile(id) }
    val diagnostics = settings.diagnostics.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val subscriptionRoute = settings.subscriptionRoute.stateIn(viewModelScope, SharingStarted.Eagerly, RouteTarget.AUTO)
    private val _subscriptionFeedback = MutableStateFlow<SubscriptionFeedback?>(null)
    val subscriptionFeedback = _subscriptionFeedback.asStateFlow()
    private val _updatingSubscription = MutableStateFlow<String?>(null)
    val updatingSubscription = _updatingSubscription.asStateFlow()
    fun setSubscriptionRoute(route: RouteTarget) = preferenceAction { settings.subscriptionRoute(route) }
    val updateRoute = settings.updateRoute.stateIn(viewModelScope, SharingStarted.Eagerly, RouteTarget.AUTO)
    private val latency = NodeLatencyTester(pool::client)
    val directHealth = latency.direct
    val nodeHealth = latency.health
    private val nodeTestPreparing = MutableStateFlow(false)
    private var nodeTestJob: Job? = null
    val nodeTesting = combine(latency.testing, nodeTestPreparing) { measuring, preparing -> measuring || preparing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val nodeTestProgress = latency.progress
    fun testNodes() {
        if (nodeTestJob?.isActive == true) return
        nodeTestJob = viewModelScope.launch {
            nodeTestPreparing.value = true
            try {
                requireNetwork(settings.options.first())
                val profiles = library.value.profiles
                withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
                nodeTestPreparing.value = false
                latency.measure(profiles)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { debugLog.error("NODE_TEST", error); _message.value = "节点测试失败，请检查网络设置" }
            finally { nodeTestPreparing.value = false }
        }
    }
    val playback = PlaybackCoordinator(application, repository, pool, viewModelScope, debugLog, latency::failed,
        subtitlesEnabled = { options.value.liveSubtitles }) { channel, stream -> guardPlaybackNetwork(channel, stream) }
    private var activeSubtitleTranslation: com.tvatlas.player.translation.SubtitleTranslation? = null
    internal val subtitleTranslation by lazy {
        com.tvatlas.player.translation.SubtitleTranslation(getApplication(), viewModelScope,
            { playback.player.value }, ::canLoadChannelArtwork,
            { debugLog.event("INFO", "TRANSLATION", it) }).also { controller ->
            activeSubtitleTranslation = controller
            viewModelScope.launch { playback.player.collect { controller.bind(it) } }
        }
    }
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val operations = kotlinx.coroutines.sync.Mutex()
    private val routeChanges = kotlinx.coroutines.sync.Mutex()
    private var playJob: Job? = null
    private var playIntent = 0L
    private var restartingConfiguration = false
    private val _update = MutableStateFlow(UpdateState())
    val update = _update.asStateFlow()
    private var updateJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadedApk: java.io.File? = null

    private val _coreInfo = MutableStateFlow(CoreInfo(BuildConfig.MIHOMO_VERSION, android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), false, false))
    val coreInfo = _coreInfo.asStateFlow()
    fun refreshCoreInfo() { viewModelScope.launch { _coreInfo.value = withContext(Dispatchers.IO) { core.info() } } }
    fun checkUpdate() {
        if (BuildConfig.BUILD_TYPE == "preview") { _message.value = "预览版不连接正式版更新通道"; return }
        debugLog.event("INFO", "UPDATE", "check requested")
        if (_update.value.checking || _update.value.downloading) return
        downloadedApk = null
        _update.value = UpdateState(checking = true)
        updateJob = viewModelScope.launch {
            try {
                val release = withUpdateNetwork(false) { client -> UpdateRepository(client, log = debugLog).latestAsync() }
                _update.value = UpdateState(checked = true, release = release)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                debugLog.error("UPDATE", error)
                _update.value = UpdateState(error = if (error is UpdateException || error is IllegalArgumentException) error.message else "连接 GitHub 失败，请切换下方更新网络后重试，或打开仓库查看安装包")
            }
        }
    }
    fun setUpdateRoute(route: RouteTarget) = preferenceAction { settings.updateRoute(route) }
    fun openRepository() {
        try {
            getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { _message.value = "没有可用的浏览器，请复制仓库链接到其他设备打开" }
    }
    fun cancelUpdate() {
        updateJob?.cancel(); downloadJob?.cancel()
        _update.update { it.copy(checking = false, downloading = false, error = "操作已取消") }
    }
    private suspend fun <T> withUpdateNetwork(download: Boolean, work: suspend (okhttp3.OkHttpClient) -> T): T {
        requireNetwork(settings.options.first())
        val selected = settings.updateRoute.first()
        val profiles = repository.library.first().profiles
        withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
        return com.tvatlas.player.update.withUpdateRoutes(
            com.tvatlas.player.update.updateTargets(selected, profiles), download,
            client = { pool.client(it) }, label = pool::label, log = debugLog, work = work)
    }

    fun openUpdateDownload() {
        if (BuildConfig.BUILD_TYPE == "preview") return
        val release = _update.value.release ?: return
        if (!allowCurrentNetwork(options.value)) { _message.value = networkPolicyMessage(); return }
        if (needsConsent()) { _pendingDownload.value = true; return }
        if (_update.value.downloading || release.versionCode <= BuildConfig.VERSION_CODE || release.minSdk > android.os.Build.VERSION.SDK_INT) return
        downloadedApk = null
        _update.value = _update.value.copy(downloading = true, downloadProgress = 0, apkReady = false, error = null)
        downloadJob = viewModelScope.launch {
            val file = java.io.File(getApplication<Application>().cacheDir, "updates/TVAtlas-Player-v${release.versionName}-release.apk")
            try {
                withUpdateNetwork(true) { client ->
                    _update.update { it.copy(downloadProgress = 0) }
                    ApkDownloader(client, log = debugLog).download(release, file) { progress ->
                        _update.update { it.copy(downloadProgress = progress) }
                    }
                }
                withContext(Dispatchers.IO) { verifyDownloadedApk(getApplication<Application>(), file, release) }
                downloadedApk = file
                _update.update { it.copy(downloading = false, downloadProgress = 100, apkReady = true) }
                debugLog.event("INFO", "UPDATE_DOWNLOAD", "verified version=${release.versionName}")
                _message.value = "下载完成，请点击安装更新"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                file.delete()
                debugLog.error("UPDATE_DOWNLOAD", error)
                _update.update { it.copy(downloading = false, apkReady = false,
                    error = if (error is UpdateException || error is IllegalArgumentException) error.message else "下载失败，请切换更新网络后重试") }
            }
        }
    }
    fun installUpdate() {
        if (BuildConfig.BUILD_TYPE == "preview") return
        val file = downloadedApk ?: return
        if (!_update.value.apkReady || !file.isFile) { _message.value = "安装包已清理，请重新下载"; return }
        val app = getApplication<Application>()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                _message.value = "请允许 TVAtlas 安装应用，返回后点击安装更新"
                app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { _message.value = "无法打开系统安装界面，可复制下载链接安装" }
    }

    private val connectivity = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    private val television = (application.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    @Volatile private var meteredAllowed = false
    private val _pendingDownload = MutableStateFlow(false)
    val pendingDownload = _pendingDownload.asStateFlow()
    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()
    private var initializationJob: Job? = null
    private val firstFrame = CompletableDeferred<Unit>()
    private val _startup = MutableStateFlow(StartupState())
    val startup = _startup.asStateFlow()
    fun onFirstFrame() { firstFrame.complete(Unit) }

    fun retryStartup() {
        if (initializationJob?.isActive == true || _restoring.value) return
        initializeLibrary()
    }

    private fun initializeLibrary() {
        initializationJob = viewModelScope.launch {
            firstFrame.await()
            _startup.value = StartupState()
            try {
                repository.initializeDefaults(onProgress = { completed, total ->
                    _startup.value = StartupState(localReady = true, completed = completed, total = total)
                })
                _startup.update { it.copy(running = false) }
                if (autoRefreshJob?.isActive != true) autoRefreshJob = viewModelScope.launch { refreshDue() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                debugLog.error("STARTUP_DEFAULTS", error)
                _startup.update { it.copy(running = false, failed = true) }
            }
        }
    }
    private var networkIdentity: Pair<android.net.Network?, Boolean>? = null
    private val _pendingPlay = MutableStateFlow<Pair<String, String?>?>(null)
    val pendingPlay = _pendingPlay.asStateFlow()
    private var limitWarned = false
    private var autoRefreshJob: Job? = null
    private var officialGuideJob: Job? = null
    private var guideJob: Job? = null
    private var guideCacheJob: Job? = null
    private suspend fun resourceBytes(url: String, maximum: Int, tag: String): ByteArray {
        requireNetwork(settings.options.first())
        val selected = settings.subscriptionRoute.first()
        val profiles = repository.library.first().profiles
        withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
        return com.tvatlas.player.update.withUpdateRoutes(subscriptionTargets(selected, profiles), false,
            client = { pool.client(it) }, label = pool::label, log = debugLog, routeTag = "${tag}_ROUTE", compatTag = "${tag}_COMPAT") { base ->
            val request = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) TVAtlas-Resources").build()
            val localized = if (request.url.host == "nowplayer.now.com") request.newBuilder().header("Cookie", "LANG=zh").build() else request
            com.tvatlas.player.network.fetchResourceBytes(base, localized, maximum, debugLog, tag)
        }
    }
    val epg = com.tvatlas.player.epg.EpgStore(java.io.File(application.noBackupFilesDir, "epg/cache.json"), viewModelScope, channels = { library.value.channels }) { url ->
        resourceBytes(url, com.tvatlas.core.epg.XmlTv.MAX_BYTES, "EPG")
    }
    private val backup = BackupManager(database, vault, settings)
    private val officialGuideAt = mutableMapOf<String, Long>()
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { networkChanged() }
        override fun onLost(network: android.net.Network) { networkChanged() }
        override fun onAvailable(network: android.net.Network) { networkChanged() }
    }
    private fun networkChanged() {
        viewModelScope.launch {
            val mobile = isMobileNetwork()
            val key = connectivity.activeNetwork to mobile
            if (key != networkIdentity) { networkIdentity = key; latency.invalidate() }
            if (!mobile) meteredAllowed = false
            if (!allowCurrentNetwork(options.value) || needsConsent()) {
                cloudActive?.cancelReading("网络已切换，请确认网络使用范围后重新读取")
                vodActive?.pause()
            }
            val status = playback.status.value
            if ((status.loading || playback.player.value != null) && (!allowCurrentNetwork(options.value) || needsConsent())) {
                stopPlayback()
                if (allowCurrentNetwork(options.value)) status.channelId?.let { _pendingPlay.value = it to status.selectedStreamId }
                else _message.value = networkPolicyMessage()
            }
            if (_update.value.downloading && (!allowCurrentNetwork(options.value) || needsConsent())) {
                cancelUpdate()
                _update.update { it.copy(error = "网络已切换，下载已停止；请确认网络设置后重新下载") }
                debugLog.event("INFO", "UPDATE_DOWNLOAD", "cancelled by network policy")
            }
        }
    }
    fun canLoadChannelArtwork(): Boolean = allowCurrentNetwork(options.value) && !needsConsent()
    fun isMetered(): Boolean = connectivity.activeNetwork != null && connectivity.isActiveNetworkMetered
    private fun isMobileNetwork(): Boolean {
        val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
            (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && isMetered()) ||
            (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && isMetered())
    }
    private fun allowCurrentNetwork(prefs: UserOptions): Boolean = connectivity.activeNetwork == null ||
        com.tvatlas.player.network.networkAllowed(prefs.networkAccess, isMobileNetwork(), television)
    private fun networkPolicyMessage() = "当前网络不符合设置，请连接${options.value.networkAccess.label}或调整网络使用范围"
    private fun needsConsent() = com.tvatlas.player.network.needsMobileConsent(television, isMobileNetwork(), options.value.warnMetered, meteredAllowed)
    private fun requireNetwork(prefs: UserOptions) { if (!allowCurrentNetwork(prefs)) throw IllegalArgumentException(networkPolicyMessage()) }
    private fun guardPlaybackNetwork(channel: Channel, stream: String?): Boolean {
        if (_restoring.value || restartingConfiguration) { _message.value = "正在更新配置，请稍后播放"; return false }
        if (!allowCurrentNetwork(options.value)) { _message.value = networkPolicyMessage(); return false }
        if (needsConsent()) { _pendingPlay.value = channel.id to stream; return false }
        return true
    }
    fun approveMetered() {
        val cloud = pendingCloudAction; pendingCloudAction = null; _pendingCloudConsent.value = false
        val request = _pendingPlay.value; val download = _pendingDownload.value
        _pendingPlay.value = null; _pendingDownload.value = false; meteredAllowed = true
        request?.let { (id, stream) -> library.value.channels.firstOrNull { it.id == id }?.let { play(it, stream) } }
        if (download) openUpdateDownload()
        cloud?.invoke()
    }
    fun dismissMetered() { _pendingPlay.value = null; _pendingDownload.value = false; pendingCloudAction = null; _pendingCloudConsent.value = false }
    fun editOptions(edit: (UserOptions) -> UserOptions) = preferenceAction { settings.editOptions(edit) }
    fun toggleFavorite(channel: Channel) = editOptions { it.copy(favorites = if (channel.id in it.favorites) it.favorites - channel.id else it.favorites + channel.id) }
    private fun ensureGuideCache(): Job = guideCacheJob ?: viewModelScope.launch {
        val started = android.os.SystemClock.elapsedRealtime()
        debugLog.event("INFO", "GUIDE_CACHE", "begin after user request")
        epg.load(settings.options.first().epgUrl)
        debugLog.event("INFO", "GUIDE_CACHE", "complete elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
    }.also { guideCacheJob = it }
    fun refreshGuide() {
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            ensureGuideCache().join()
            epg.refresh(settings.options.first().epgUrl)
        }
    }
    private fun requestChannelGuide(channel: Channel) {
        officialGuideJob?.cancel()
        officialGuideJob = viewModelScope.launch {
            // Loading cached data must finish before a network result can replace it.
            ensureGuideCache().join()
            refreshOfficialGuide(channel)
            val url = settings.options.first().epgUrl
            if (!epg.hasOfficial(channel) && url.isNotBlank() && epg.needsRefresh()) {
                guideJob?.join()
                if (epg.needsRefresh()) epg.refresh(url)
            }
        }
    }
    fun saveGuideUrl(url: String) = preferenceAction { settings.editOptions { it.copy(epgUrl = url) }; refreshGuide() }
    private suspend fun refreshOfficialGuide(channel: Channel) {
        val now = System.currentTimeMillis()
        if (now - (officialGuideAt[channel.id] ?: 0) < 3600000 && epg.hasOfficial(channel)) return
        val official = com.tvatlas.player.source.MyTvSuperSource.channels.firstOrNull { it.tvgId == channel.tvgId || it.name == channel.name }
        val url = if (official != null) {
            val format = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Hong_Kong") }
            "https://content-api.mytvsuper.com/v1/epg?network_code=${official.network}&from=${format.format(java.util.Date(now))}&to=${format.format(java.util.Date(now + 86400000))}&platform=web"
        } else when (channel.tvgId ?: channel.name) {
            "ViuTV.hk", "ViuTV" -> "https://nowplayer.now.com/tvguide/epglist?channelIdList[]=099&day=1"
            "ViuTVsix.hk", "ViuTVsix" -> "https://nowplayer.now.com/tvguide/epglist?channelIdList[]=096&day=1"
            else -> return
        }
        if (epg.official(channel, url, official != null)) officialGuideAt[channel.id] = now
    }
    fun exportBackup(uri: Uri, password: CharArray) = action("加密备份已导出，请妥善保管密码") {
        val bytes = routeChanges.withLock { backup.export(password) }
        withContext(Dispatchers.IO) { requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
    }
    fun restoreBackup(uri: Uri, password: CharArray) = action("配置已恢复") {
        _restoring.value = true
        try {
        initializationJob?.cancelAndJoin(); autoRefreshJob?.cancelAndJoin(); officialGuideJob?.cancelAndJoin(); guideJob?.cancelAndJoin(); guideCacheJob?.cancelAndJoin()
        stopPlayback()
        val bytes = withContext(Dispatchers.IO) { requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { input ->
            val out = ByteArrayOutputStream(); val b = ByteArray(8192)
            while (true) { val n = input.read(b); if (n < 0) break; require(out.size() + n <= com.tvatlas.core.backup.BackupCipher.MAX_BYTES + 52); out.write(b, 0, n) }; out.toByteArray() } }
        try { routeChanges.withLock { backup.restore(bytes, password) } }
        catch (error: java.security.GeneralSecurityException) { throw IllegalArgumentException("密码错误或备份已损坏，原配置已保留") }
        synchronizeSubscriptionClients(); epg.reset(settings.options.first().epgUrl); officialGuideAt.clear()
        } finally { password.fill('\u0000'); _restoring.value = false; _startup.update { it.copy(running = false) } }
    }
    private suspend fun refreshDue() {
        val prefs = settings.options.first()
        if (!prefs.autoRefresh || isMetered() || playback.player.value != null || vodActive?.state?.value?.player != null || !operations.tryLock()) return
        _busy.value = true
        try {
            val now = System.currentTimeMillis(); val interval = prefs.refreshHours * 3600000L
            if (vodActive?.state?.value?.player == null) {
                activeVodSubscriptions?.sync(interval = interval)
                activeUnifiedVod?.refreshDue(interval)
            }
            repository.library.first().playlists.filter { it.enabled && now - (it.lastUpdatedAt ?: 0) >= interval }.forEach { playlist ->
                try { repository.refresh(playlist); epg.invalidateLibrary() } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { debugLog.error("AUTO_REFRESH", e) }
            }
            database.dao().subscriptions().first().filter { now - it.lastUpdatedAt >= interval }.forEach { sub ->
                try { subscriptionRepository.refresh(sub.id); routeChanges.withLock { synchronizeSubscriptionClients() } }
                catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { debugLog.error("AUTO_REFRESH", e) }
            }
        } finally { _busy.value = false; operations.unlock() }
    }
    init {
        debugLog.event("INFO", "STARTUP", "view-model ready elapsedMs=${android.os.SystemClock.elapsedRealtime() - constructionStarted}")
        initializeLibrary()
        viewModelScope.launch { library.first { it.channels.isNotEmpty() }.let { debugLog.event("INFO", "STARTUP", "library ready channels=${it.channels.size} nodes=${it.profiles.size}") } }
        // Check due dates hourly; subscriptions retain their configured 24-hour default.
        viewModelScope.launch { while (isActive) { delay(3600000); if (autoRefreshJob?.isActive != true) autoRefreshJob = viewModelScope.launch { refreshDue() } } }
        viewModelScope.launch {
            while (isActive) {
                delay(60000)
                val target = playback.status.value.attempt?.target
                if (playback.player.value != null && target != null) {
                    if (target.type == RouteType.DIRECT) latency.measureDirect()
                    else target.profile?.let { id -> latency.measure(library.value.profiles.filter { it.id == id }, staleOnly = true) }
                }
            }
        }
        viewModelScope.launch {
            combine(traffic.state, settings.options) { usage, prefs -> usage.direct + usage.proxy to prefs.limitMiB }.collect { (bytes, limit) ->
                if (limit == 0L || bytes < limit * 1048576L) limitWarned = false
                else if (!limitWarned) { limitWarned = true; _message.value = "累计流量已达到提醒值，请查看流量统计" }
            }
        }
        runCatching { connectivity.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback) }
        viewModelScope.launch {
            var previousProfiles = emptyList<ProxyProfile>()
            library.collect { state ->
                if (previousProfiles != state.profiles) {
                    latency.invalidate()
                    withContext(Dispatchers.IO) { core.update(state.profiles); pool.update(state.profiles) }
                    previousProfiles = state.profiles
                    debugLog.event("INFO", "PROXY_CONFIG", "updated nodes=${state.profiles.size}")
                }
            }
        }
    }
    /** Short route edits are serialized separately from network downloads. Playback waits only for these edits. */
    private fun routeAction(success: String? = null, work: suspend () -> Unit) {
        viewModelScope.launch {
            try { routeChanges.withLock { work() }; if (success != null) _message.value = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { debugLog.error("SETTINGS", error); _message.value = "设置未保存，请重试" }
        }
    }
    /** Local settings must not queue behind slow subscription/playlist network operations. */
    private fun preferenceAction(work: suspend () -> Unit) {
        viewModelScope.launch {
            try { routeChanges.withLock { work() }; networkChanged() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { debugLog.error("SETTINGS", error); _message.value = "设置未保存，请重试" }
        }
    }
    private fun action(success: String? = null, work: suspend () -> Unit) {
        viewModelScope.launch {
            operations.lock()
            _busy.value = true
            try { work(); debugLog.event("INFO", "SETTINGS", success ?: "operation completed"); if (success != null) _message.value = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                debugLog.error("SETTINGS", error)
                // Validation errors are controlled by our code. Parser/network messages may contain private data.
                _message.value = if (error is IllegalArgumentException && error !is kotlinx.serialization.SerializationException)
                    error.message?.take(200) ?: "输入无效" else "操作失败，请检查输入配置或网络连接"
            } finally { _busy.value = false; operations.unlock() }
        }
    }
    fun dismissMessage() { _message.value = null }
    private val _playlistFeedback = MutableStateFlow<String?>(null)
    val playlistFeedback = _playlistFeedback.asStateFlow()
    fun refreshBuiltIn() = action {
        _playlistFeedback.value = "正在刷新 TVAtlas 频道订阅…"
        val result = repository.refreshBuiltIn()
        epg.invalidateLibrary()
        _playlistFeedback.value = result
        _message.value = result
    }
    fun refresh(playlist: Playlist) = action("播放列表已更新") { debugLog.event("INFO", "PLAYLIST", "refresh ${playlist.name}"); repository.refresh(playlist); epg.invalidateLibrary() }
    fun editPlaylist(playlist: Playlist, name: String, url: String) = action("频道源已保存") {
        require(name.isNotBlank()) { "请输入频道源名称" }
        resumeAroundChange { repository.refresh(playlist.copy(name = name.trim(), url = url.trim())) }
        epg.invalidateLibrary()
    }
    fun enablePlaylist(playlist: Playlist) = action("频道源状态已更新") {
        resumeAroundChange { repository.enablePlaylist(playlist.id, !playlist.enabled) }
        epg.invalidateLibrary()
    }
    fun deletePlaylist(id: String) = action("频道源已删除") {
        require(DefaultPlaylists.entries.none { it.id == id }) { "内置来源不可删除" }
        resumeAroundChange {
            if (id.startsWith("unified-live:")) library.value.playlists.firstOrNull { it.id == id }?.let { vodSubscriptions.ignoreLive(it.url) }
            repository.removePlaylist(id)
        }
        epg.invalidateLibrary()
    }
    fun addPlaylist(name: String, url: String) = action("播放列表已添加") {
        require(name.isNotBlank()) { "请输入播放列表名称" }
        repository.refresh(Playlist(stableId(url.trim()), name.trim(), url.trim()))
        epg.invalidateLibrary()
    }
    private var livePageActive = true
    fun play(channel: Channel, streamId: String? = null, forceRestart: Boolean = false) {
        if (!livePageActive) return
        val status = playback.status.value
        if (com.tvatlas.player.playback.LivePlayRequest.shouldReuse(channel.id, streamId, status,
                playback.player.value?.playbackState in listOf(2, 3), forceRestart)) {
            debugLog.event("INFO", "PLAY", "existing request retained; loading=${status.loading}")
            return
        }
        activeSubtitleTranslation?.bind(null)
        vodActive?.close()
        if (!guardPlaybackNetwork(channel, streamId)) return
        playIntent++
        val refreshing = autoRefreshJob; refreshing?.cancel()
        requestChannelGuide(channel)
        debugLog.event("INFO", "PLAY", "requested channel=${channel.name} line=${streamId?.let { "selected" } ?: "automatic"}")
        val routes = library.value.profiles.filter { it.enabled }
        debugLog.event("INFO", "PROXY", "available=" + routes.joinToString { "${it.id}:${it.name}:${it.type}" })
        playJob?.cancel(); playback.stop(false); playback.preparing(channel, streamId)
        playJob = viewModelScope.launch {
            try {
                refreshing?.join()
                // Wait for queued route edits and read a fresh database snapshot before resolving.
                val current = routeChanges.withLock { repository.library.first() }
                val fresh = current.channels.firstOrNull { it.id == channel.id } ?: channel
                withContext(Dispatchers.IO) { core.update(current.profiles); pool.update(current.profiles) }
                val selectedStream = streamId?.takeIf { id -> fresh.streams.any { it.id == id } }
                if (streamId != null && selectedStream == null) _message.value = "线路已更新，改为自动选择"
                if (!guardPlaybackNetwork(fresh, selectedStream)) { playback.stop(); return@launch }
                // Playback history and existing health choose the first attempt; connection failures
                // advance the existing failover session. Never block playback on a full node sweep.
                debugLog.event("INFO", "PLAY_ROUTE", "using saved success and current health; preflight sweep skipped")
                playback.play(fresh, DefaultRouteResolver(current.rules, current.profiles, latency.health.value), selectedStream)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { debugLog.error("PLAY_PREPARATION", error); playback.preparationFailed(channel, streamId) }
        }
    }

    /** Leaving live cancels pending route resolution as well as an already created player. */
    fun setLivePageActive(active: Boolean) {
        livePageActive = active
        if (active) return
        _pendingPlay.value = null
        playIntent++; playJob?.cancel()
        if (playback.player.value != null || playback.status.value.loading) playback.stop()
    }
    fun stopPlayback(showMessage: Boolean = true) { playIntent++; playJob?.cancel(); playback.stop(showMessage); vodActive?.pause() }
    private suspend fun synchronizeSubscriptionClients() = withContext(Dispatchers.IO) {
        // Read the committed database snapshot rather than a possibly stale StateFlow value.
        latency.invalidate()
        core.close()
        val profiles = repository.library.first().profiles
        core.update(profiles); pool.update(profiles, true)
    }
    private suspend fun resumeAroundChange(work: suspend () -> Unit) {
        val status = playback.status.value
        val resume = status.channelId?.takeIf { playback.player.value != null || status.loading }
        if (resume != null) { stopPlayback(false); _message.value = "更新配置后将恢复播放" }
        val intent = playIntent
        restartingConfiguration = true
        try { work() }
        finally {
            restartingConfiguration = false
            if (resume != null && !_restoring.value && playIntent == intent && currentCoroutineContext().isActive) {
                val channel = repository.library.first().channels.firstOrNull { it.id == resume }
                if (channel != null) play(channel, status.selectedStreamId)
                else { playback.stop(); _message.value = "当前频道来源已移除，请选择其他频道" }
            }
        }
    }
    private suspend fun coordinatedSubscriptionChange(work: suspend () -> Unit) = resumeAroundChange {
        work(); routeChanges.withLock { synchronizeSubscriptionClients() }
    }
    fun addSubscription(name: String, url: String) = action {
        _updatingSubscription.value = "new"
        _subscriptionFeedback.value = null
        try {
            debugLog.event("INFO", "SUBSCRIPTION", "import requested")
            var skipped = 0
            coordinatedSubscriptionChange { skipped = subscriptionRepository.add(name, url) }
            val message = "订阅导入成功" + if (skipped > 0) "，已忽略 $skipped 个说明或不支持的条目" else ""
            _subscriptionFeedback.value = SubscriptionFeedback(null, message)
            _message.value = message
        } catch (error: SubscriptionException) {
            _subscriptionFeedback.value = SubscriptionFeedback(null, error.message ?: "订阅导入失败", true)
            throw error
        } finally { _updatingSubscription.value = null }
    }
    fun refreshSubscription(id: String) = action {
        _updatingSubscription.value = id
        _subscriptionFeedback.value = null
        try {
            debugLog.event("INFO", "SUBSCRIPTION", "refresh requested")
            var skipped = 0
            coordinatedSubscriptionChange { skipped = subscriptionRepository.refresh(id) }
            val message = "订阅已更新，节点设置已保留" + if (skipped > 0) "；忽略 $skipped 个说明或不支持的条目" else ""
            _subscriptionFeedback.value = SubscriptionFeedback(id, message)
            _message.value = message
        } catch (error: SubscriptionException) {
            _subscriptionFeedback.value = SubscriptionFeedback(id, error.message ?: "更新失败，已保留旧节点", true)
            throw error
        } finally { _updatingSubscription.value = null }
    }
    fun deleteSubscription(id: String) = action("订阅及其节点已移除") {
        coordinatedSubscriptionChange { subscriptionRepository.remove(id) }
    }
    fun enableNode(profile: ProxyProfile) = routeAction {
        require(profile.type == ProxyType.MIHOMO)
        val current = repository.library.first().profiles.firstOrNull { it.id == profile.id } ?: return@routeAction
        resumeAroundChange { repository.saveProxy(current.copy(enabled = !current.enabled)); synchronizeSubscriptionClients() }
    }
    fun resetChannelRoutes(channelId: String) = routeAction("已恢复自动路由，重新播放后生效") { repository.resetChannelRoutes(channelId) }
    fun setRoute(channelId: String, streamId: String?, target: RouteTarget?) = routeAction("路由已保存，重新播放后生效") {
        if (streamId == null) repository.setChannelRoute(channelId, target) else repository.setStreamRoute(streamId, target)
    }
    fun saveProxy(profile: ProxyProfile, credentials: Credentials?, replaceCredentials: Boolean) = routeAction("代理已保存") {
        RuleCodec.validateProxy(profile)
        if (credentials != null) {
            require(credentials.username.isNotBlank() && credentials.password.isNotBlank()) { "用户名和密码需同时填写" }
            if (profile.type == ProxyType.SOCKS5) {
                require(credentials.username.toByteArray().size <= 255 && credentials.password.toByteArray().size <= 255) { "SOCKS5 凭证过长" }
            }
        }
        if (replaceCredentials) withContext(Dispatchers.IO) { vault.put(profile.id, credentials) }
        repository.saveProxy(profile)
        withContext(Dispatchers.IO) { pool.update(repository.library.first().profiles, credentialsChanged = true) }
    }
    fun importRules(uri: Uri) = action("规则导入成功") {
        val text = withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("无法打开文件")
            stream.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= RuleCodec.MAX_BYTES) { "规则文件超过 1 MB" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        }
        val name = withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull()
        }
        repository.importRules(text, library.value.profiles, name)
    }
    fun clearLogs() { debugLog.clear() }
    fun exportLogs(uri: Uri, category: com.tvatlas.player.debug.LogCategory = com.tvatlas.player.debug.LogCategory.ALL) = action("日志已导出") {
        val text = debugLog.snapshot(category)
        withContext(Dispatchers.IO) {
            val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("无法创建日志文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
    }
    fun exportRules(uri: Uri) = action("规则已导出（不包含凭证）") {
        val state = library.value
        val json = RuleCodec.export(state.rules.copy(proxies = state.profiles.filter { it.type != ProxyType.MIHOMO }))
        withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("无法创建文件")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
    }
    fun showDiagnostics(value: Boolean) = preferenceAction { settings.diagnostics(value) }
    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        updateJob?.cancel()
        downloadJob?.cancel()
        activeSubtitleTranslation?.close()
        vodActive?.close()
        stopPlayback(false); core.close(); pool.close(); playlistClient.dispatcher.cancelAll(); database.close(); traffic.close()
        if (getApplication<Application>() !is AtlasApplication) debugLog.close()
    }
}
