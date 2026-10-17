package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import com.tvatlas.core.model.stableId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.io.File

@Serializable private data class FeedSnapshot(val url: String, val at: Long, val sources: List<SubscribedVodSource>)
internal data class VodSubscriptionsState(val sources: List<SubscribedVodSource>, val busy: Boolean = false,
    val message: String = "尚未同步，当前使用内置可用接口", val feeds: List<String> = emptyList())
/** Successful feed snapshots replace their own contribution. Failed/empty feeds never erase working sources. */
internal class VodSubscriptionStore(private val directory: File, private val bootstrap: List<SubscribedVodSource>,
    private val scope: CoroutineScope, feeds: Map<String, String>, private val fetch: suspend (String) -> String,
    private val validate: suspend (String) -> Boolean) {
    private val seeds = feeds.entries.take(8).associate { it.key to it.value }
    private val _state = MutableStateFlow(VodSubscriptionsState(bootstrap, feeds = seeds.keys.toList()))
    val state = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val snapshots = linkedMapOf<String, FeedSnapshot>()
    private var job: Job? = null
    private var lastAttempt = 0L
    private val initialized = scope.launch {
        val saved = withContext(Dispatchers.IO) { seeds.values.mapNotNull { url ->
            runCatching { val file = file(url); if (!file.isFile || file.length() > 2 * 1024 * 1024) null else
                json.decodeFromString<FeedSnapshot>(file.readText()).takeIf { it.url == url && it.sources.isNotEmpty() && it.sources.size <= 40 } }.getOrNull()
        } }
        saved.forEach { snapshots[it.url] = it }
        publish()
        if (saved.isNotEmpty()) _state.value = _state.value.copy(message = "当前使用上次同步的订阅记录")
    }
    private fun file(url: String) = File(directory, stableId(url) + ".json")
    private fun publish() {
        if (snapshots.isNotEmpty()) _state.value = _state.value.copy(sources = snapshots.values.flatMap { it.sources }.distinctBy { it.api }.take(60))
    }
    fun sync(force: Boolean = false, interval: Long = 86400000L): Job {
        job?.takeIf { it.isActive }?.let { return it }
        val next = scope.launch {
            initialized.join()
            val now = System.currentTimeMillis()
            if (!force && now - lastAttempt < interval) return@launch
            val due = seeds.filterValues { force || now - (snapshots[it]?.at ?: 0L) >= interval }
            if (due.isEmpty()) return@launch
            lastAttempt = now
            _state.value = _state.value.copy(busy = true, message = "读取原始订阅并验证原生接口…")
            val notes = mutableListOf<String>(); var successes = 0
            try {
                for ((name, url) in due) {
                    try {
                        val parsed = withTimeout(20000) { VodSubscription.parse(fetch(url), url) }
                        if (parsed.sources.isEmpty()) { notes += "$name：无原生 JSON 接口（${parsed.unsupported} 个入口未支持），保留旧配置"; continue }
                        val permits = Semaphore(3)
                        val good = coroutineScope { parsed.sources.map { candidate -> async {
                            permits.withPermit {
                                try { withTimeout(7000) { candidate.takeIf { validate(it.api) } } }
                                catch (timeout: TimeoutCancellationException) { null }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            }
                        } }.awaitAll().filterNotNull() }
                        if (good.isEmpty()) { notes += "$name：接口验证未通过，保留旧配置"; continue }
                        // If an old interface is still declared but currently fails, retain it for this feed.
                        val retained = (snapshots[url]?.sources ?: bootstrap).filter { old ->
                            old.name in parsed.unsupportedNames || parsed.sources.any { it.name == old.name } && good.none { it.name == old.name }
                        }
                        val snapshot = FeedSnapshot(url, System.currentTimeMillis(), (good + retained).distinctBy { it.api })
                        withContext(Dispatchers.IO) {
                            directory.mkdirs(); val part = File(file(url).path + ".tmp")
                            part.writeText(json.encodeToString(snapshot)); check(part.renameTo(file(url))) { "保存订阅失败" }
                        }
                        snapshots[url] = snapshot; successes++; publish()
                        notes += "$name：${good.size} 个已验证，${parsed.unsupported} 个非原生入口未支持"
                    } catch (timeout: TimeoutCancellationException) { notes += "$name：超时，保留旧配置" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notes += "$name：读取或格式不支持，保留旧配置" }
                }
                _state.value = _state.value.copy(message = "已同步 $successes 个订阅\n" + notes.joinToString("\n"))
            } finally { _state.value = _state.value.copy(busy = false) }
        }
        job = next
        return next
    }
}
