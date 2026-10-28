package com.tvatlas.player.vod

import com.tvatlas.core.model.stableId
import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class UnifiedVodState(val items: List<UnifiedMovie> = emptyList(), val loading: Boolean = false,
    val completed: Int = 0, val total: Int = 0, val failed: Int = 0, val canLoadMore: Boolean = true,
    val error: String? = null, val notice: String? = null, val showingCache: Boolean = false, val cacheUpdatedAt: Long? = null)
@Serializable private data class ShelfOffer(val source: String, val movie: CatalogMovie)
@Serializable private data class CachedShelf(val updated: Long, val offers: List<ShelfOffer>)
@Serializable private data class CachedCatalog(val updated: Long, val page: CatalogPage)

/** Incremental, bounded catalogue browsing. Only metadata is persisted; playable URLs are refreshed on selection. */
internal class UnifiedVodRepository(private var sources: List<String>, private val directory: File,
    private val scope: CoroutineScope, private val log: com.tvatlas.player.debug.DebugLog? = null,
    private val health: VodSourceHealth? = null, private val healthContext: (String) -> String = { "direct" },
    private val canRecordHealth: (Boolean) -> Boolean = { true },
    private val diskLock: Mutex = Mutex(), private val parallelism: Int = 3, private val client: suspend (String) -> VodCatalogClient) {
    private val _state = MutableStateFlow(UnifiedVodState())
    val state = _state.asStateFlow()
    private val types = ConcurrentHashMap<String, List<CatalogType>>()
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    private var generation = 0L
    private var currentKey = ""
    private var round = 0
    private var offers = emptyList<VodOffer>()
    private var lastFilter: VodFilter? = null
    private var lastQuery = ""
    private var lastCacheOnly = false
    private var refreshedAt = 0L
    private var visible = true
    private var interrupted = false
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            interrupted = interrupted || job?.isActive == true
            job?.cancel(); generation++
            _state.value = _state.value.copy(loading = false)
            log?.event("INFO", "VOD_BROWSE", "paused; browsing cancelled, loaded items retained")
        }
    }
    /** Called by the existing idle/background refresh loop; never instantiates this repository at startup. */
    fun updateSources(value: List<String>) {
        val next = value.distinct().take(60)
        if (next == sources) return
        sources = next
        offers = offers.filter { it.source in next }
        if (next.isEmpty()) {
            job?.cancel(); generation++
            _state.value = UnifiedVodState(canLoadMore = false, notice = "尚未添加影视源，可在频道源中导入统一订阅。")
            return
        }
        // Subscription discovery may finish after the page's first browse has already started.
        // The repository owns restarting that request; a UI effect must not race this cancellation.
        val filter = lastFilter
        if (filter != null && visible) browse(filter, lastQuery, force = true, cacheOnly = lastCacheOnly)
        else _state.value = _state.value.copy(total = next.size)
    }
    fun refreshDue(interval: Long = 86400000L) {
        if (!visible) return
        val filter = lastFilter ?: return
        if (interrupted) { browse(filter, lastQuery); return }
        if (lastQuery.isBlank() && !_state.value.loading && System.currentTimeMillis() - refreshedAt >= interval)
            browse(filter, lastQuery, force = true, cacheOnly = lastCacheOnly)
    }
    fun browse(filter: VodFilter, query: String, more: Boolean = false, force: Boolean = false, cacheOnly: Boolean = false) {
        if (!visible) return
        val key = "$filter|$query"
        if (more && (_state.value.loading || !_state.value.canLoadMore)) return
        val append = more && currentKey == key && !interrupted
        interrupted = false
        val previous = if (currentKey == key) offers else emptyList()
        val previousCacheTime = _state.value.cacheUpdatedAt
        lastFilter = filter; lastQuery = query; lastCacheOnly = cacheOnly
        job?.cancel(); val token = ++generation
        if (!append) { offers = emptyList(); round = 0 }
        currentKey = key; round++
        val requestedRound = round
        _state.value = UnifiedVodState(if (append || previous.isNotEmpty()) _state.value.items else emptyList(), loading = true, total = sources.size, showingCache = previous.isNotEmpty())
        job = scope.launch {
            if (!append) {
                val savedShelf = if (previous.isNotEmpty()) previous to previousCacheTime else loadShelf(key, filter, query)
                val saved = savedShelf.first
                if (token != generation) return@launch
                offers = saved
                val cachedItems = withContext(Dispatchers.Default) { UnifiedVod.sorted(UnifiedVod.merge(saved), filter.sort) }
                if (token != generation) return@launch
                _state.value = _state.value.copy(showingCache = saved.isNotEmpty(), items = cachedItems, cacheUpdatedAt = savedShelf.second)
            }
            if (cacheOnly) {
                _state.value = _state.value.copy(loading = false, canLoadMore = false,
                    notice = if (offers.isEmpty()) "暂无缓存，可点击更新片单。" else "当前显示缓存片单，可点击更新。")
                return@launch
            }
            val sourceSnapshot = sources
            val contexts = sourceSnapshot.associateWith(healthContext)
            val available = withContext(Dispatchers.Default) { sourceSnapshot.filter { health?.eligible(it, contexts.getValue(it)) != false }
                .sortedBy { health?.get(it, contexts.getValue(it))?.latencyMs ?: Long.MAX_VALUE } }
            val cooling = sources.size - available.size
            _state.value = _state.value.copy(total = available.size)
            if (cooling > 0) _state.value = _state.value.copy(notice = "$cooling 个来源暂缓访问，可在频道源中重新检查")
            val permits = Semaphore(parallelism)
            var completed = 0; var failed = 0; var hasMore = false
            try {
                log?.event("INFO", "VOD_BROWSE", "start round=$requestedRound sources=${sources.size} search=${query.isNotBlank()}")
                coroutineScope {
                    available.map { source -> launch {
                        val context = contexts.getValue(source)
                        var contacted = false
                        var started = 0L
                        val result = permits.withPermit {
                            started = System.nanoTime()
                            try {
                                withTimeout(12000) { fetch(source, requestedRound, filter, query, force) { contacted = true } }
                            } catch (timeout: TimeoutCancellationException) { null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        }
                        if (token != generation) return@launch
                        if (contacted && canRecordHealth(result != null)) health?.record(source, context, result != null, (System.nanoTime() - started) / 1_000_000)
                        if (token != generation) return@launch
                        completed++; if (result == null) failed++
                        if (result != null && result.page < result.pages) hasMore = true
                        if (result != null) offers = ((if (append) offers else offers.filterNot { it.source == source }) + result.movies.filter { UnifiedVod.matches(it, filter) }
                            .map { VodOffer(source, it.copy(episodes = emptyList())) }).distinctBy { it.source to it.movie.id }.take(1200)
                        val snapshot = offers; val revision = completed
                        val merged = withContext(Dispatchers.Default) { UnifiedVod.sorted(UnifiedVod.merge(snapshot), filter.sort) }
                        if (token == generation && revision >= _state.value.completed) {
                            _state.value = _state.value.copy(items = merged, completed = revision, showingCache = failed == completed && merged.isNotEmpty(), cacheUpdatedAt = if (failed < completed) System.currentTimeMillis() else _state.value.cacheUpdatedAt,
                                failed = failed, canLoadMore = offers.size < 1200,
                                error = if (offers.size >= 1200) "本次已加载 1200 条记录，请使用分类或搜索缩小范围" else null)
                            if (result != null && snapshot.isNotEmpty()) saveShelf(key, snapshot)
                        }
                    } }.joinAll()
                }
                if (token == generation) log?.event("INFO", "VOD_BROWSE", "complete round=$requestedRound items=${offers.size} failed=$failed hasMore=$hasMore")
                if (token == generation && failed < available.size) {
                    refreshedAt = System.currentTimeMillis()
                    saveShelf(key, offers)
                }
            } finally {
                if (token == generation) _state.value = _state.value.copy(loading = false,
                    canLoadMore = hasMore && offers.size < 1200,
                    error = _state.value.error ?: if (available.isNotEmpty() && failed == available.size && _state.value.items.isEmpty()) "暂时无法加载片单，请检查网络或稍后重试" else null,
                    notice = if (sources.isEmpty()) "尚未添加影视源，可在频道源中导入统一订阅。"
                        else if (available.isEmpty()) "来源暂缓访问，可在频道源中点击重新检查；已有片单仍可浏览。"
                        else if (failed == available.size && _state.value.items.isNotEmpty()) "当前显示上次浏览的片单，连接恢复后可刷新。"
                        else if (failed > 0 && _state.value.items.isNotEmpty()) "已显示当前可用片单，部分内容可稍后再刷新。" else if (cooling > 0) "$cooling 个来源暂缓访问，可在频道源中重新检查" else null)
            }
        }
    }
    private suspend fun fetch(source: String, number: Int, filter: VodFilter, query: String, force: Boolean, networkUsed: () -> Unit): CatalogPage {
        if (source.startsWith("stremio:")) {
            if (filter.kind !in setOf(VodKind.MOVIE, VodKind.SERIES)) return CatalogPage(emptyList(), number, number)
            val type = if (filter.kind == VodKind.MOVIE) "movie" else "series"
            val key = "addon|$type|$number|$query"
            return (if (force) null else cached(source, key)) ?: run {
                val offset = (1 until number).sumOf { previous -> cached(source, "addon|$type|$previous|$query")?.movies?.size ?: 0 }
                networkUsed(); client(source).load(source, number, query, type = type, addonOffset = offset)
            }.also { save(source, key, it) }
        }
        // Recent feed first: avoid waiting for taxonomy and rotating an arbitrary genre on entry.
        if (query.isBlank() && filter.genre == "全部" && filter.region == "全部" && filter.year == "全部") {
            val key = "recent|$number"
            val recent = (if (force) null else cached(source, key)) ?: run { networkUsed(); client(source).load(source, number, "") }.also { save(source, key, it) }
            if (recent.movies.any { UnifiedVod.matches(it, filter) }) return recent
        }
        val sourceTypes = (if (force) null else types[source]) ?: run {
            val cached = if (force) null else cached(source, "types")
            val found = cached ?: try { run { networkUsed(); client(source).load(source, 1, "", listingOnly = true) }.also { save(source, "types", it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { CatalogPage(emptyList(), 1, 1) }
            found.types.also { types[source] = it }
        }
        val ids = if (query.isBlank()) UnifiedVod.categoryIds(sourceTypes, filter.kind, filter.primaryGroup) else emptyList()
        val category = ids.takeIf { it.isNotEmpty() }?.let { it[(number - 1 + sources.indexOf(source).coerceAtLeast(0)) % it.size] }
        val page = if (ids.isNotEmpty()) (number - 1) / ids.size + 1 else number
        val key = "$category|$page|$query|${filter.year}|${filter.region}"
        val result = (if (force) null else cached(source, key)) ?: run { networkUsed(); client(source).load(source, page, query, type = category, year = filter.year.takeIf { it.matches(Regex("\\d{4}")) }, area = filter.region.takeUnless { it in listOf("全部", "其他", "欧美", "日韩", "港台") }) }.also { save(source, key, it) }
        return result.copy(page = number, pages = (result.pages * ids.size.coerceAtLeast(1)).coerceAtMost(100000))
    }
    suspend fun details(selected: UnifiedMovie): UnifiedMovie = coroutineScope {
        val permits = Semaphore(4)
        val found = mutableListOf<VodOffer>()
        withTimeoutOrNull(9000) {
            sources.filter { source -> !selected.movie.discoveryOnly || selected.offers.any { it.source == source } }.sortedBy { source -> selected.offers.none { it.source == source } }.map { source -> launch {
                permits.withPermit {
                    val context = healthContext(source)
                    if (health?.eligible(source, context) == false) return@withPermit
                    var failed = false
                    val started = System.nanoTime()
                    val results = try {
                        withTimeout(6000) {
                            val api = client(source)
                            val known = selected.offers.filter { it.source == source }
                            val movies = if (known.isNotEmpty()) known.take(2).flatMap { offer ->
                                val details = api.load(source, 1, "", offer.movie.id).movies
                                if (details.isEmpty() && source.startsWith("stremio:") && offer.movie.typeId == "movie")
                                    listOf(offer.movie.copy(episodes = listOf(com.tvatlas.core.vod.stremio.StremioProtocol.pendingEpisode(source, offer.movie.id, "正片"))))
                                else details
                            }
                                else api.load(source, 1, selected.movie.name).movies.take(30)
                            movies.filter { UnifiedVod.identity(it) == UnifiedVod.identity(selected.movie) || source.startsWith("stremio:") && known.any { offer -> offer.movie.id == it.id } }.map { VodOffer(source, it) }
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        failed = true
                        emptyList()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                        emptyList()
                    }
                    if (canRecordHealth(!failed)) health?.record(source, context, !failed, (System.nanoTime() - started) / 1_000_000)
                    found.addAll(results)
                }
            } }.joinAll()
        }
        if (found.isEmpty() && selected.movie.discoveryOnly) return@coroutineScope selected
        if (found.isEmpty()) throw IllegalArgumentException("暂时未找到可用播放信息，请稍后重试")
        withContext(Dispatchers.Default) { UnifiedVod.merge(found.toList()).first() }
    }
    private suspend fun loadShelf(key: String, filter: VodFilter, query: String): Pair<List<VodOffer>, Long?> = withContext(Dispatchers.IO) {
        val exact = File(directory, "shelf-" + stableId(key) + ".json")
        val candidates = listOf(exact) + directory.listFiles().orEmpty()
            .filter { it.name.startsWith("shelf-") && it.extension == "json" && it != exact }
            .sortedByDescending { it.lastModified() }.take(12)
        val found = mutableListOf<VodOffer>()
        var updated: Long? = null
        var bytes = 0L
        for (file in candidates) {
            if (!file.isFile || file.length() > 8 * 1024 * 1024 || bytes + file.length() > 16 * 1024 * 1024) continue
            bytes += file.length()
            val cached = runCatching { json.decodeFromString<CachedShelf>(file.readText()) }.getOrNull() ?: continue
            if (System.currentTimeMillis() - cached.updated !in 0..604800000L) continue
            val startSize = found.size
            found += cached.offers.asSequence().filter { it.source in sources && UnifiedVod.matches(it.movie, filter) &&
                (query.isBlank() || it.movie.name.contains(query.trim(), ignoreCase = true)) }
                .take(1200).map { VodOffer(it.source, it.movie.copy(episodes = emptyList())) }.toList()
            if (found.size > startSize) updated = minOf(updated ?: cached.updated, cached.updated)
            if (file == exact && found.isNotEmpty() || found.size >= 1200) break
        }
        found.distinctBy { it.source to it.movie.id }.take(1200) to updated
    }
    private suspend fun saveShelf(key: String, offers: List<VodOffer>) = diskLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val file = File(directory, "shelf-" + stableId(key) + ".json")
                val part = File(file.path + ".tmp")
                part.writeText(json.encodeToString(CachedShelf(System.currentTimeMillis(), offers.map { ShelfOffer(it.source, it.movie.copy(episodes = emptyList())) })))
                if (!part.renameTo(file)) part.delete()
                directory.listFiles()?.filter { it.name.startsWith("shelf-") && it.extension == "json" }
                    ?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
                val files = directory.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
                var bytes = files.sumOf { it.length() }
                var count = files.size
                files.forEach { old -> if (count > 80 || bytes > 32L * 1024 * 1024) { bytes -= old.length(); count--; old.delete() } }
            }
        }
    }
    private suspend fun cached(source: String, key: String): CatalogPage? = withContext(Dispatchers.IO) {
        val file = File(directory, stableId("$source|$key") + ".json")
        runCatching {
            if (!file.isFile || file.length() > 4 * 1024 * 1024) return@runCatching null
            val record = json.decodeFromString<CachedCatalog>(file.readText())
            record.page.takeIf { System.currentTimeMillis() - record.updated < if (key == "types") 86400000L else 86400000L }
        }.getOrNull()
    }
    private suspend fun save(source: String, key: String, page: CatalogPage) = diskLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val file = File(directory, stableId("$source|$key") + ".json")
                val metadata = page.copy(movies = page.movies.map { it.copy(episodes = emptyList()) })
                val part = File(file.path + ".tmp")
                part.writeText(json.encodeToString(CachedCatalog(System.currentTimeMillis(), metadata)))
                if (!part.renameTo(file)) part.delete()
                val files = directory.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
                var bytes = files.sumOf { it.length() }; var count = files.size
                files.forEach { old -> if (count > 80 || bytes > 32L * 1024 * 1024) { bytes -= old.length(); count--; old.delete() } }
            }
        }
    }
}
