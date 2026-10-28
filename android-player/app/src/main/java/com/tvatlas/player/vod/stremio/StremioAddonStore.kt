package com.tvatlas.player.vod.stremio

import com.tvatlas.core.model.stableId
import com.tvatlas.core.vod.stremio.AddonManifest
import com.tvatlas.player.storage.CredentialVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Serializable internal data class AddonEntry(val address: String, val manifest: AddonManifest, val enabled: Boolean = true) {
    val source: String get() = "stremio:" + stableId(address)
    override fun toString() = "AddonEntry(${manifest.name}, address=[redacted])"
}
internal data class AddonStoreState(val entries: List<AddonEntry> = emptyList(), val loading: Boolean = true, val busy: Boolean = false, val error: String? = null)
/** Configured URLs can contain credentials. Only opaque source IDs leave this encrypted store. */
internal class StremioAddonStore(private val vault: CredentialVault, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var readable = true
    private val _state = MutableStateFlow(AddonStoreState())
    val state = _state.asStateFlow()
    private val loaded = scope.launch(Dispatchers.IO) {
        lock.withLock {
            try { val data = vault.secret("addons"); val entries = if (data == null) listOf(AddonEntry(com.tvatlas.core.vod.stremio.OpenFilmsAddon.ADDRESS, com.tvatlas.core.vod.stremio.OpenFilmsAddon.manifest)) else json.decodeFromString<List<AddonEntry>>(data).take(30)
                _state.value = AddonStoreState(entries, loading = false)
            } catch (_: Exception) { readable = false; _state.value = AddonStoreState(loading = false, error = "插件配置暂不可读，已保留原数据") }
        }
    }
    fun find(source: String) = state.value.entries.firstOrNull { it.source == source && it.enabled }
    fun catalogSources() = state.value.entries.filter { it.enabled && it.manifest.catalogs.isNotEmpty() }.map { it.source }
    suspend fun add(address: String, client: OkHttpClient) {
        loaded.join()
        val clean = StremioAddonClient.address(address)
        _state.update { it.copy(busy = true, error = null) }
        try {
            val manifest = StremioAddonClient(client).manifest(clean)
            lock.withLock { save((_state.value.entries.filterNot { it.address == clean } + AddonEntry(clean, manifest)).also { require(it.size <= 30) { "最多添加 30 个插件" } }) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _state.update { it.copy(error = if (error is IllegalArgumentException) error.message else "无法读取插件，请检查网络或地址") } }
        finally { _state.update { it.copy(busy = false) } }
    }
    suspend fun enable(source: String, enabled: Boolean) { loaded.join(); lock.withLock { save(_state.value.entries.map { if (it.source == source) it.copy(enabled = enabled) else it }) } }
    suspend fun remove(source: String) { loaded.join(); lock.withLock { save(_state.value.entries.filterNot { it.source == source }) } }
    private suspend fun save(entries: List<AddonEntry>) {
        check(readable) { "原插件配置暂不可读，不能覆盖" }
        withContext(Dispatchers.IO) { vault.putSecret("addons", json.encodeToString(entries)) }
        _state.update { it.copy(entries = entries, error = null) }
    }
}
