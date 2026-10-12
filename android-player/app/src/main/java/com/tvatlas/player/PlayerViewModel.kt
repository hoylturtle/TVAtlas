package com.tvatlas.player

import android.app.Application
import android.net.Uri
import android.content.Intent
import com.tvatlas.player.update.*
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
import java.io.ByteArrayOutputStream

data class SubscriptionFeedback(val id: String?, val message: String, val error: Boolean = false)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val debugLog = com.tvatlas.player.debug.DebugLog(java.io.File(application.noBackupFilesDir, "debug/player.log"),
        "TVAtlas ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    private val database = PlayerDatabase.open(application)
    private val vault = CredentialVault(application)
    private val core = MihomoRuntime(application, vault)
    private val pool = ProxyClientPool(vault, core)
    private val playlistClient = pool.direct.newBuilder().followRedirects(true).followSslRedirects(true)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private val repository = PlayerRepository(database, playlistClient, debugLog)
    private val subscriptionRepository = SubscriptionRepository(database, playlistClient, vault, core, debugLog) { url ->
        val profiles = library.value.profiles
        val selected = settings.subscriptionRoute.first()
        withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
        SubscriptionDownloader(pool::client, debugLog).download(url, subscriptionTargets(selected, profiles))
    }
    val subscriptions = subscriptionRepository.subscriptions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val settings = SettingsStore(application)
    val library = repository.library.stateIn(viewModelScope, SharingStarted.Eagerly, Library(emptyList(), emptyList(), emptyList(), RuleConfig()))
    val diagnostics = settings.diagnostics.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val subscriptionRoute = settings.subscriptionRoute.stateIn(viewModelScope, SharingStarted.Eagerly, RouteTarget.AUTO)
    private val _subscriptionFeedback = MutableStateFlow<SubscriptionFeedback?>(null)
    val subscriptionFeedback = _subscriptionFeedback.asStateFlow()
    private val _updatingSubscription = MutableStateFlow<String?>(null)
    val updatingSubscription = _updatingSubscription.asStateFlow()
    fun setSubscriptionRoute(route: RouteTarget) = action { settings.subscriptionRoute(route) }
    val updateRoute = settings.updateRoute.stateIn(viewModelScope, SharingStarted.Eagerly, RouteTarget.DIRECT)
    private val latency = NodeLatencyTester(pool::client)
    val nodeHealth = latency.health
    val nodeTesting = latency.testing
    fun testNodes() {
        viewModelScope.launch {
            val profiles = library.value.profiles
            withContext(Dispatchers.IO) { core.update(profiles); pool.update(profiles) }
            latency.measure(profiles)
        }
    }
    val playback = PlaybackCoordinator(application, repository, pool, viewModelScope, debugLog, latency::failed)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val operations = kotlinx.coroutines.sync.Mutex()
    private var playJob: Job? = null
    private val _update = MutableStateFlow(UpdateState())
    val update = _update.asStateFlow()
    private var updateJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadedApk: java.io.File? = null

    private val _coreInfo = MutableStateFlow(CoreInfo(BuildConfig.MIHOMO_VERSION, android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), false, false))
    val coreInfo = _coreInfo.asStateFlow()
    fun refreshCoreInfo() { viewModelScope.launch { _coreInfo.value = withContext(Dispatchers.IO) { core.info() } } }
    fun checkUpdate() {
        debugLog.event("INFO", "UPDATE", "check requested")
        if (_update.value.checking || _update.value.downloading) return
        downloadedApk = null
        _update.value = UpdateState(checking = true)
        updateJob = viewModelScope.launch {
            try {
                val client = updateClient(false)
                val release = UpdateRepository(client).latestAsync()
                _update.value = UpdateState(checked = true, release = release)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                debugLog.error("UPDATE", error)
                _update.value = UpdateState(error = if (error is UpdateException) error.message else "连接 GitHub 失败，请切换下方更新网络后重试，或打开仓库查看安装包")
            }
        }
    }
    fun setUpdateRoute(route: RouteTarget) = action { settings.updateRoute(route) }
    fun openRepository() {
        try {
            getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { _message.value = "没有可用的浏览器，请复制仓库链接到其他设备打开" }
    }
    private suspend fun updateClient(download: Boolean): okhttp3.OkHttpClient = withContext(Dispatchers.IO) {
        val target = settings.updateRoute.first()
        val profiles = library.value.profiles
        core.update(profiles); pool.update(profiles)
        debugLog.event("INFO", "UPDATE_ROUTE", "route=${target.type} proxy=${pool.label(target)} download=$download")
        pool.client(target).newBuilder().followRedirects(true).followSslRedirects(false)
            .connectTimeout(if (download) 10 else 5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(if (download) 30 else 8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(if (download) 300 else 8, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    fun openUpdateDownload() {
        val release = _update.value.release ?: return
        if (_update.value.downloading || release.versionCode <= BuildConfig.VERSION_CODE || release.minSdk > android.os.Build.VERSION.SDK_INT) return
        downloadedApk = null
        _update.value = _update.value.copy(downloading = true, downloadProgress = 0, apkReady = false, error = null)
        downloadJob = viewModelScope.launch {
            val file = java.io.File(getApplication<Application>().cacheDir, "updates/TVAtlas-Player-v${release.versionName}-release.apk")
            try {
                ApkDownloader(updateClient(true)).download(release, file) { progress ->
                    _update.update { it.copy(downloadProgress = progress) }
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
                    error = if (error is UpdateException) error.message else "下载失败，请切换更新网络后重试") }
            }
        }
    }
    fun installUpdate() {
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

    init {
        action { repository.initializeDefaults() }
        viewModelScope.launch {
            library.collect { state ->
                withContext(Dispatchers.IO) { core.update(state.profiles); pool.update(state.profiles) }
            }
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
        _playlistFeedback.value = result
        _message.value = result
    }
    fun refresh(playlist: Playlist) = action("播放列表已更新") { debugLog.event("INFO", "PLAYLIST", "refresh ${playlist.name}"); repository.refresh(playlist) }
    fun addPlaylist(name: String, url: String) = action("播放列表已添加") {
        require(name.isNotBlank()) { "请输入播放列表名称" }
        repository.refresh(Playlist(stableId(url.trim()), name.trim(), url.trim()))
    }
    fun play(channel: Channel, streamId: String? = null) {
        debugLog.event("INFO", "PLAY", "requested channel=${channel.name} line=${streamId?.let { "selected" } ?: "automatic"}")
        val routes = library.value.profiles.filter { it.enabled }
        debugLog.event("INFO", "PROXY", "available=" + routes.joinToString { "${it.id}:${it.name}:${it.type}" })
        playJob?.cancel(); playback.stop(false)
        playJob = viewModelScope.launch {
            val current = library.value
            withContext(Dispatchers.IO) { core.update(current.profiles); pool.update(current.profiles) }
            val initial = DefaultRouteResolver(current.rules, current.profiles).channelPlan(channel, null, streamId)
            val ids = initial.attempts.mapNotNull { it.target.profile }.toSet()
            if (ids.isNotEmpty()) {
                debugLog.event("INFO", "NODE_TEST", "automatic region=${channel.playbackRegion()?.label} candidates=${ids.size}")
                latency.measure(current.profiles.filter { it.id in ids }, staleOnly = true)
            }
            playback.play(channel, DefaultRouteResolver(current.rules, current.profiles, latency.health.value), streamId)
        }
    }
    fun stopPlayback(showMessage: Boolean = true) { playJob?.cancel(); playback.stop(showMessage) }
    private suspend fun synchronizeSubscriptionClients() = withContext(Dispatchers.IO) {
        // Read the committed database snapshot rather than a possibly stale StateFlow value.
        latency.invalidate()
        val profiles = repository.library.first().profiles
        core.update(profiles); pool.update(profiles, true)
    }
    fun addSubscription(name: String, url: String) = action {
        _updatingSubscription.value = "new"
        _subscriptionFeedback.value = null
        try {
            debugLog.event("INFO", "SUBSCRIPTION", "import requested")
            val skipped = subscriptionRepository.add(name, url)
            synchronizeSubscriptionClients()
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
            val skipped = subscriptionRepository.refresh(id)
            synchronizeSubscriptionClients()
            val message = "订阅已更新，节点设置已保留" + if (skipped > 0) "；忽略 $skipped 个不支持的节点" else ""
            _subscriptionFeedback.value = SubscriptionFeedback(id, message)
            _message.value = message
        } catch (error: SubscriptionException) {
            _subscriptionFeedback.value = SubscriptionFeedback(id, error.message ?: "更新失败，已保留旧节点", true)
            throw error
        } finally { _updatingSubscription.value = null }
    }
    fun deleteSubscription(id: String) = action("订阅及其节点已移除") {
        subscriptionRepository.remove(id); synchronizeSubscriptionClients()
    }
    fun enableNode(profile: ProxyProfile) = action {
        require(profile.type == ProxyType.MIHOMO)
        repository.saveProxy(profile.copy(enabled = !profile.enabled, subscriptionId = profile.subscriptionId))
    }
    fun setRoute(channelId: String, streamId: String?, target: RouteTarget?) = action("路由已保存，重新播放后生效") {
        if (streamId == null) repository.setChannelRoute(channelId, target) else repository.setStreamRoute(streamId, target)
    }
    fun saveProxy(profile: ProxyProfile, credentials: Credentials?, replaceCredentials: Boolean) = action("代理已保存") {
        RuleCodec.validateProxy(profile)
        if (credentials != null) {
            require(credentials.username.isNotBlank() && credentials.password.isNotBlank()) { "用户名和密码需同时填写" }
            if (profile.type == ProxyType.SOCKS5) {
                require(credentials.username.toByteArray().size <= 255 && credentials.password.toByteArray().size <= 255) { "SOCKS5 凭证过长" }
            }
        }
        if (replaceCredentials) withContext(Dispatchers.IO) { vault.put(profile.id, credentials) }
        repository.saveProxy(profile)
        pool.update(library.value.profiles, credentialsChanged = true)
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
        repository.importRules(text, library.value.profiles)
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
    fun showDiagnostics(value: Boolean) = action { settings.diagnostics(value) }
    override fun onCleared() {
        updateJob?.cancel()
        downloadJob?.cancel()
        stopPlayback(false); core.close(); pool.close(); playlistClient.dispatcher.cancelAll(); database.close(); debugLog.close()
    }
}
