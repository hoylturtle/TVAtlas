package com.tvatlas.player

import android.app.Application
import android.net.Uri
import android.content.Intent
import com.tvatlas.player.update.*
import com.tvatlas.player.network.readCancellable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvatlas.core.model.*
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

data class SubscriptionFeedback(val id: String?, val message: String, val error: Boolean = false)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val debugLog = com.tvatlas.player.debug.DebugLog(java.io.File(application.noBackupFilesDir, "debug/player.log"),
        "TVAtlas ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    private val database = PlayerDatabase.open(application)
    private val vault = CredentialVault(application)
    private val core = MihomoRuntime(application, vault)
    val traffic = com.tvatlas.player.traffic.TrafficStore(java.io.File(application.noBackupFilesDir, "traffic/usage.json"))
    private val pool = ProxyClientPool(vault, core, traffic)
    private val playlistClient = pool.direct.newBuilder().followRedirects(true).followSslRedirects(true)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
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
    private val repository = PlayerRepository(database, playlistClient, debugLog) { url -> resourceBytes(url, 8 * 1024 * 1024, "PLAYLIST").toString(Charsets.UTF_8) }
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
    val nodeTesting = latency.testing
    fun testNodes() {
        viewModelScope.launch {
            val profiles = library.value.profiles
            withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
            latency.measure(profiles)
        }
    }
    val playback = PlaybackCoordinator(application, repository, pool, viewModelScope, debugLog, latency::failed) { channel, stream -> guardPlaybackNetwork(channel, stream) }
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
    private var meteredAllowed = false
    private val _pendingDownload = MutableStateFlow(false)
    val pendingDownload = _pendingDownload.asStateFlow()
    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()
    private var initializationJob: Job? = null
    private var networkIdentity: Pair<android.net.Network?, Boolean>? = null
    private val _pendingPlay = MutableStateFlow<Pair<String, String?>?>(null)
    val pendingPlay = _pendingPlay.asStateFlow()
    private var limitWarned = false
    private var autoRefreshJob: Job? = null
    private var officialGuideJob: Job? = null
    private var guideJob: Job? = null
    private suspend fun resourceBytes(url: String, maximum: Int, tag: String): ByteArray {
        requireNetwork(settings.options.first())
        val selected = settings.subscriptionRoute.first()
        val profiles = repository.library.first().profiles
        withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
        return com.tvatlas.player.update.withUpdateRoutes(subscriptionTargets(selected, profiles), false,
            client = { pool.client(it) }, label = pool::label, log = debugLog, routeTag = "${tag}_ROUTE", compatTag = "${tag}_COMPAT") { base ->
            val client = base.newBuilder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
            val request = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) TVAtlas-Resources").build()
            val localized = if (request.url.host == "nowplayer.now.com") request.newBuilder().header("Cookie", "LANG=zh").build() else request
            client.newCall(localized).readCancellable { response ->
                debugLog.event("INFO", "${tag}_HTTP", "host=${response.request.url.host} status=${response.code}")
                if (!response.isSuccessful) throw UpdateException("资源请求失败", response.code, response.protocol)
                val input = requireNotNull(response.body).byteStream().buffered()
                input.mark(2); val first = input.read(); val second = input.read(); input.reset()
                val decoded = if (first == 0x1f && second == 0x8b) java.util.zip.GZIPInputStream(input) else input
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                decoded.use { while (true) { val count = it.read(buffer); if (count < 0) break
                    require(out.size() + count <= maximum) { "资源文件过大" }; out.write(buffer, 0, count) } }
                out.toByteArray()
            }
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
            if (!allowCurrentNetwork(options.value) || needsConsent()) cloudActive?.cancelReading("网络已切换，请确认网络使用范围后重新读取")
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
    fun refreshGuide() { guideJob?.cancel(); guideJob = viewModelScope.launch { epg.refresh(settings.options.first().epgUrl) } }
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
        initializationJob?.cancelAndJoin(); autoRefreshJob?.cancelAndJoin(); officialGuideJob?.cancelAndJoin(); guideJob?.cancelAndJoin()
        stopPlayback()
        val bytes = withContext(Dispatchers.IO) { requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { input ->
            val out = ByteArrayOutputStream(); val b = ByteArray(8192)
            while (true) { val n = input.read(b); if (n < 0) break; require(out.size() + n <= com.tvatlas.core.backup.BackupCipher.MAX_BYTES + 52); out.write(b, 0, n) }; out.toByteArray() } }
        try { routeChanges.withLock { backup.restore(bytes, password) } }
        catch (error: java.security.GeneralSecurityException) { throw IllegalArgumentException("密码错误或备份已损坏，原配置已保留") }
        synchronizeSubscriptionClients(); epg.reset(settings.options.first().epgUrl); officialGuideAt.clear(); refreshGuide()
        } finally { password.fill('\u0000'); _restoring.value = false }
    }
    private suspend fun refreshDue() {
        val prefs = settings.options.first()
        if (!prefs.autoRefresh || isMetered() || playback.player.value != null || !operations.tryLock()) return
        _busy.value = true
        try {
            val now = System.currentTimeMillis(); val interval = prefs.refreshHours * 3600000L
            repository.library.first().playlists.filter { it.enabled && now - (it.lastUpdatedAt ?: 0) >= interval }.forEach { playlist ->
                try { repository.refresh(playlist); epg.invalidateLibrary() } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { debugLog.error("AUTO_REFRESH", e) }
            }
            database.dao().subscriptions().first().filter { now - it.lastUpdatedAt >= interval }.forEach { sub ->
                try { subscriptionRepository.refresh(sub.id); routeChanges.withLock { synchronizeSubscriptionClients() } }
                catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { debugLog.error("AUTO_REFRESH", e) }
            }
            if (epg.needsRefresh()) epg.refresh(prefs.epgUrl)
        } finally { _busy.value = false; operations.unlock() }
    }
    init {
        initializationJob = viewModelScope.launch { repository.initializeDefaults(); autoRefreshJob = viewModelScope.launch { refreshDue() } }
        guideJob = viewModelScope.launch { library.first { it.channels.isNotEmpty() }; val prefs = settings.options.first(); epg.load(prefs.epgUrl); if (!isMetered() && epg.needsRefresh()) epg.refresh(prefs.epgUrl) }
        viewModelScope.launch { while (isActive) { delay(900000); autoRefreshJob?.cancelAndJoin(); autoRefreshJob = viewModelScope.launch { refreshDue() } } }
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
                if (previousProfiles != state.profiles) { latency.invalidate(); previousProfiles = state.profiles }
                withContext(Dispatchers.IO) { core.update(state.profiles); pool.update(state.profiles) }
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
        resumeAroundChange { repository.removePlaylist(id) }
        epg.invalidateLibrary()
    }
    fun addPlaylist(name: String, url: String) = action("播放列表已添加") {
        require(name.isNotBlank()) { "请输入播放列表名称" }
        repository.refresh(Playlist(stableId(url.trim()), name.trim(), url.trim()))
        epg.invalidateLibrary()
    }
    fun play(channel: Channel, streamId: String? = null) {
        if (!guardPlaybackNetwork(channel, streamId)) return
        playIntent++
        val refreshing = autoRefreshJob; refreshing?.cancel()
        officialGuideJob?.cancel(); officialGuideJob = viewModelScope.launch { refreshOfficialGuide(channel) }
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
                val initial = DefaultRouteResolver(current.rules, current.profiles).channelPlan(fresh, null, selectedStream)
                val ids = initial.attempts.mapNotNull { it.target.profile }.toSet()
                if (initial.attempts.any { it.target.type == RouteType.DIRECT }) viewModelScope.launch { latency.measureDirect() }
                if (ids.isNotEmpty()) {
                    playback.preparing(fresh, streamId)
                    debugLog.event("INFO", "NODE_TEST", "automatic region=${fresh.playbackRegion()?.label} candidates=${ids.size}")
                    latency.measure(current.profiles.filter { it.id in ids }, staleOnly = true)
                }
                playback.play(fresh, DefaultRouteResolver(current.rules, current.profiles, latency.health.value), selectedStream)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { debugLog.error("PLAY_PREPARATION", error); playback.preparationFailed(channel, streamId) }
        }
    }

    fun stopPlayback(showMessage: Boolean = true) { playIntent++; playJob?.cancel(); playback.stop(showMessage) }
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
            val message = "订阅导入成功" + if (skipped > 0) "，已忽略 $skipped 个不支持的节点" else ""
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
            val message = "订阅已更新，节点设置已保留" + if (skipped > 0) "；忽略 $skipped 个不支持的节点" else ""
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
    fun exportLogs(uri: Uri) = action("日志已导出") {
        val text = debugLog.snapshot()
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
        stopPlayback(false); core.close(); pool.close(); playlistClient.dispatcher.cancelAll(); database.close(); debugLog.close(); traffic.close()
    }
}
