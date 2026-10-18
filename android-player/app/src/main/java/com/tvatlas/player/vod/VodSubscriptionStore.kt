package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import com.tvatlas.core.model.stableId
import com.tvatlas.core.model.httpUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.io.File

@Serializable private data class FeedSnapshot(val url: String, val at: Long, val sources: List<SubscribedVodSource>,
    val live: List<SubscribedLiveSource> = emptyList())
@Serializable private data class FeedPreferences(val custom: Map<String, String> = emptyMap(),
    val removed: Set<String> = emptySet(), val ignoredLive: Set<String> = emptySet())
internal data class VodSubscriptionsState(val sources: List<SubscribedVodSource>, val busy: Boolean = false,
    val message: String = "尚未同步，当前使用内置可用接口", val feeds: List<String> = emptyList(),
    val subscriptions: Map<String, String> = emptyMap(), val liveCount: Int = 0)

/** Owned feed contributions, not a replacement for the user's manual sources. No script execution. */
internal class VodSubscriptionStore(private val directory: File, private val bootstrap: List<SubscribedVodSource>,
    private val scope: CoroutineScope, feeds: Map<String, String>, private val fetch: suspend (String) -> String,
    private val validate: suspend (String) -> Boolean,
    private val applyLive: suspend (List<SubscribedLiveSource>, Set<String>) -> Unit = { _, _ -> }) {
    private val defaults = feeds.entries.take(8).associate { it.value.trim() to it.key }
    private var seeds = defaults
    private var preferences = FeedPreferences()
    private val _state = MutableStateFlow(VodSubscriptionsState(bootstrap, feeds = feeds.keys.toList(), subscriptions = defaults))
    val state = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val snapshots = linkedMapOf<String, FeedSnapshot>()
    private var job: Job? = null
    private val lastAttempt = mutableMapOf<String, Long>()
    private val mutations = kotlinx.coroutines.sync.Mutex()
    private val initialized = scope.launch {
        val saved = withContext(Dispatchers.IO) {
            preferences = runCatching { val file = File(directory, "preferences.json")
                if (!file.isFile || file.length() > 256_000) FeedPreferences() else json.decodeFromString<FeedPreferences>(file.readText())
            }.getOrDefault(FeedPreferences()).let { it.copy(custom = it.custom.entries.take(12).filter { entry -> httpUri(entry.key) != null }.associate { entry -> entry.key to entry.value.take(80) },
                removed = it.removed.take(100).toSet(), ignoredLive = it.ignoredLive.take(1000).toSet()) }
            seeds = (defaults.filterKeys { stableId(it) !in preferences.removed } + preferences.custom).entries.take(12).associate { it.toPair() }
            seeds.keys.mapNotNull { url -> runCatching { val file = file(url)
                if (!file.isFile || file.length() > 2 * 1024 * 1024) null else
                    json.decodeFromString<FeedSnapshot>(file.readText()).takeIf { it.url == url &&
                        (it.sources.isNotEmpty() || it.live.isNotEmpty()) && it.sources.size <= 40 && it.live.size <= 30 }
            }.getOrNull() }
        }
        saved.forEach { snapshots[it.url] = it }
        publish()
        if (saved.isNotEmpty()) _state.value = _state.value.copy(message = "当前使用上次同步的订阅记录")
    }
    private fun file(url: String) = File(directory, stableId(url) + ".json")
    private fun liveUnion(values: Collection<FeedSnapshot> = snapshots.values) = values.flatMap { it.live }
        .filterNot { stableId(it.url) in preferences.ignoredLive }.distinctBy { it.url }.take(60)
    private fun publish() {
        _state.value = _state.value.copy(sources = if (snapshots.isEmpty() && seeds.isNotEmpty() && preferences.removed.isEmpty()) bootstrap else snapshots.values.flatMap { it.sources }.distinctBy { it.api }.take(60),
            feeds = seeds.values.toList(), subscriptions = seeds.toMap(), liveCount = liveUnion().size)
    }
    private suspend fun savePreferences() = withContext(Dispatchers.IO) {
        directory.mkdirs(); val target = File(directory, "preferences.json"); val part = File(target.path + ".tmp")
        part.writeText(json.encodeToString(preferences)); check(part.renameTo(target)) { "保存订阅设置失败" }
    }
    suspend fun addFeed(name: String, address: String) {
        initialized.join(); job?.join(); mutations.lock()
        try {
            val url = address.trim(); require(httpUri(url) != null && url.length <= 8192) { "请输入 HTTP(S) 订阅地址" }
            require(seeds.size < 12 || url in seeds) { "最多保存 12 个统一订阅" }
            val label = name.trim().ifBlank { "统一影视订阅" }.take(80)
            val old = preferences
            preferences = preferences.copy(custom = preferences.custom + (url to label), removed = preferences.removed - stableId(url))
            try { savePreferences() } catch (error: Exception) { preferences = old; throw error }
            seeds = seeds + (url to label); publish()
        } finally { mutations.unlock() }
        sync(force = true, only = address.trim()).join()
    }
    suspend fun removeFeed(url: String) {
        initialized.join(); job?.cancelAndJoin(); mutations.lock()
        try {
            val next = snapshots.filterKeys { it != url }
            val removedLive = liveUnion().map { it.url }.toSet() - liveUnion(next.values).map { it.url }.toSet()
            val old = preferences
            preferences = preferences.copy(custom = preferences.custom - url, removed = preferences.removed + stableId(url))
            try { savePreferences(); applyLive(emptyList(), removedLive) }
            catch (error: Exception) { preferences = old; runCatching { savePreferences() }; throw error }
            seeds = seeds - url; snapshots.remove(url)
            withContext(Dispatchers.IO) { file(url).delete() }
            publish()
        } finally { mutations.unlock() }
    }
    /** A manual deletion is remembered across refreshes and restarts, including shared live sources. */
    suspend fun ignoreLive(url: String) {
        initialized.join(); job?.cancelAndJoin(); mutations.lock()
        try {
            val old = preferences; preferences = preferences.copy(ignoredLive = (preferences.ignoredLive + stableId(url)).take(1000).toSet())
            try { savePreferences() } catch (error: Exception) { preferences = old; throw error }
            publish()
        } finally { mutations.unlock() }
    }
    fun sync(force: Boolean = false, interval: Long = 86400000L, only: String? = null): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            initialized.join(); mutations.lock()
            try {
                val now = System.currentTimeMillis()
                val due = seeds.filter { (url, _) -> (only == null || only == url) &&
                    (force || now - maxOf(snapshots[url]?.at ?: 0L, lastAttempt[url] ?: 0L) >= interval) }
                if (due.isEmpty()) return@launch
                due.keys.forEach { lastAttempt[it] = now }
                _state.value = _state.value.copy(busy = true, message = "正在同步影视与直播订阅…")
                val notes = mutableListOf<String>(); var successes = 0
                for ((url, name) in due) {
                    try {
                        val parsed = withTimeout(20000) { val body = fetch(url); withContext(Dispatchers.Default) { MediaSubscription.parse(body, url) } }
                        val permits = Semaphore(3)
                        val good = if (parsed.native == null) parsed.vod else coroutineScope { parsed.vod.map { candidate -> async {
                            permits.withPermit {
                                try { withTimeout(7000) { candidate.takeIf { validate(it.api) } } }
                                catch (timeout: TimeoutCancellationException) { null }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            }
                        } }.awaitAll().filterNotNull() }
                        if (good.isEmpty() && parsed.live.isEmpty()) { notes += "$name：暂无可同步的原生接口，保留上次记录"; continue }
                        val retained = if (parsed.native == null) emptyList() else (snapshots[url]?.sources ?: bootstrap).filter { old ->
                            old.name in parsed.native?.unsupportedNames.orEmpty() || parsed.vod.any { it.name == old.name } && good.none { it.name == old.name }
                        }
                        val snapshot = FeedSnapshot(url, System.currentTimeMillis(), (good + retained).distinctBy { it.api }, parsed.live)
                        val next = snapshots + (url to snapshot)
                        // New live lists must be readable before their old contribution can be removed.
                        val desiredLive = liveUnion(next.values)
                        val removedLive = liveUnion().map { it.url }.toSet() - desiredLive.map { it.url }.toSet()
                        applyLive(desiredLive, removedLive)
                        withContext(Dispatchers.IO) { directory.mkdirs(); val part = File(file(url).path + ".tmp")
                            part.writeText(json.encodeToString(snapshot)); check(part.renameTo(file(url))) { "保存订阅失败" } }
                        snapshots[url] = snapshot; successes++; publish()
                        notes += "$name：${good.size} 个影视源，${parsed.live.size} 个直播列表" +
                            (parsed.native?.unsupported?.takeIf { it > 0 }?.let { "，$it 个非原生入口未接入" } ?: "")
                    } catch (_: MediaSubscriptionLimitException) { notes += "$name：直播列表过大，超过 2000 频道或 6000 总线路上限，继续使用上次记录" }
                    catch (timeout: TimeoutCancellationException) { notes += "$name：响应较慢，继续使用上次记录" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notes += "$name：暂未完成同步，继续使用上次记录" }
                }
                _state.value = _state.value.copy(message = "已同步 $successes 个订阅\n" + notes.joinToString("\n"))
            } finally { _state.value = _state.value.copy(busy = false); mutations.unlock() }
        }.also { job = it }
    }
}
