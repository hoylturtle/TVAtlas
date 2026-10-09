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
import com.tvatlas.core.subscription.SubscriptionException
import com.tvatlas.player.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = PlayerDatabase.open(application)
    private val vault = CredentialVault(application)
    private val core = MihomoRuntime(application, vault)
    private val pool = ProxyClientPool(vault, core)
    private val playlistClient = pool.direct.newBuilder().followRedirects(true).followSslRedirects(true)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private val repository = PlayerRepository(database, playlistClient)
    private val subscriptionRepository = SubscriptionRepository(database, playlistClient, vault, core)
    val subscriptions = subscriptionRepository.subscriptions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val settings = SettingsStore(application)
    val library = repository.library.stateIn(viewModelScope, SharingStarted.Eagerly, Library(emptyList(), emptyList(), emptyList(), RuleConfig()))
    val diagnostics = settings.diagnostics.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val playback = PlaybackCoordinator(application, repository, pool, viewModelScope)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val operations = kotlinx.coroutines.sync.Mutex()
    private var playJob: Job? = null
    private val updateClient = okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
    private val updateRepository = UpdateRepository(updateClient)
    private val _update = MutableStateFlow(UpdateState())
    val update = _update.asStateFlow()
    private var updateJob: Job? = null

    fun checkUpdate() {
        if (_update.value.checking) return
        _update.value = UpdateState(checking = true)
        updateJob = viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { updateRepository.latest() }
                _update.value = UpdateState(checked = true, release = release)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _update.value = UpdateState(error = if (error is UpdateException) error.message else "检查更新失败，请检查网络后重试")
            }
        }
    }
    fun openUpdateDownload() {
        val release = _update.value.release ?: return
        if (release.versionCode <= BuildConfig.VERSION_CODE || release.minSdk > android.os.Build.VERSION.SDK_INT) return
        try {
            getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { _message.value = "没有可用的浏览器，请复制下载链接，在其他设备下载安装包" }
    }

    init {
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
            try { work(); if (success != null) _message.value = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Validation errors are controlled by our code. Parser/network messages may contain private data.
                _message.value = if (error is IllegalArgumentException && error !is kotlinx.serialization.SerializationException)
                    error.message?.take(200) ?: "输入无效" else "操作失败，请检查输入配置或网络连接"
            } finally { _busy.value = false; operations.unlock() }
        }
    }
    fun dismissMessage() { _message.value = null }
    fun refresh(playlist: Playlist) = action("播放列表已更新") { repository.refresh(playlist) }
    fun addPlaylist(name: String, url: String) = action("播放列表已添加") {
        require(name.isNotBlank()) { "请输入播放列表名称" }
        repository.refresh(Playlist(stableId(url.trim()), name.trim(), url.trim()))
    }
    fun play(channel: Channel, streamId: String? = null) {
        playJob?.cancel(); playback.stop(false)
        playJob = viewModelScope.launch {
            val current = library.value
            withContext(Dispatchers.IO) { core.update(current.profiles); pool.update(current.profiles) }
            playback.play(channel, DefaultRouteResolver(current.rules, current.profiles), streamId)
        }
    }
    fun stopPlayback(showMessage: Boolean = true) { playJob?.cancel(); playback.stop(showMessage) }
    fun addSubscription(name: String, url: String) = action {
        val skipped = subscriptionRepository.add(name, url)
        pool.update(library.value.profiles, true)
        _message.value = "订阅导入成功" + if (skipped > 0) "，已忽略 $skipped 个不支持的节点" else ""
    }
    fun refreshSubscription(id: String) = action("订阅已更新，节点路由设置已保留") {
        subscriptionRepository.refresh(id); pool.update(library.value.profiles, true)
    }
    fun deleteSubscription(id: String) = action("订阅及其节点已移除") {
        subscriptionRepository.remove(id); pool.update(library.value.profiles, true)
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
        updateJob?.cancel(); updateClient.dispatcher.cancelAll()
        stopPlayback(false); core.close(); pool.close(); playlistClient.dispatcher.cancelAll(); database.close()
    }
}
