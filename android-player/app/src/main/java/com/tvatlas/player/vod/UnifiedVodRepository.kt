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
    val error: String? = null, val notice: String? = null, val showingCache: Boolean = false)
@Serializable private data class ShelfOffer(val source: String, val movie: CatalogMovie)
@Serializable private data class CachedShelf(val updated: Long, val offers: List<ShelfOffer>)
@Serializable private data class CachedCatalog(val updated: Long, val page: CatalogPage)

/** Incremental, bounded catalogue browsing. Only metadata is persisted; playable URLs are refreshed on selection. */
internal class UnifiedVodRepository(private var sources: List<String>, private val directory: File,
    private val scope: CoroutineScope, private val log: com.tvatlas.player.debug.DebugLog? = null, private val client: suspend (String) -> VodCatalogClient) {
    private val _state = MutableStateFlow(UnifiedVodState())
    val state = _state.asStateFlow()
    private val types = ConcurrentHashMap<String, List<CatalogType>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val diskLock = Mutex()
    private var job: Job? = null
    private var generation = 0L
    private var currentKey = ""
    private var round = 0
    private var offers = emptyList<VodOffer>()
    private var lastFilter: VodFilter? = null
    private var lastQuery = ""
    private var refreshedAt = 0L
    /** Called by the existing idle/background refresh loop; never instantiates this repository at startup. */
    fun updateSources(value: List<String>) {
        val next = value.distinct().take(60)
        if (next.isEmpty() || next == sources) return
        sources = next
        offers = offers.filter { it.source in next }
        // Subscription discovery may finish after the page's first browse has already started.
        // The repository owns restarting that request; a UI effect must not race this cancellation.
        val filter = lastFilter
        if (filter != null) browse(filter, lastQuery, force = true)
        else _state.value = _state.value.copy(total = next.size)
    }
    fun refreshDue(interval: Long = 86400000L) {
        val filter = lastFilter ?: return
        if (lastQuery.isBlank() && !_state.value.loading && System.currentTimeMillis() - refreshedAt >= interval)
            browse(filter, lastQuery, force = true)
    }
    fun browse(filter: VodFilter, query: String, more: Boolean = false, force: Boolean = false) {
        val key = "$filter|$query"
        if (more && (_state.value.loading || !_state.value.canLoadMore)) return
        val append = more && currentKey == key
        val previous = if (currentKey == key) offers else emptyList()
        lastFilter = filter; lastQuery = query
        job?.cancel(); val token = ++generation
        if (!append) { offers = emptyList(); round = 0 }
        currentKey = key; round++
        val requestedRound = round
        _state.value = UnifiedVodState(if (append) _state.value.items else emptyList(), loading = true, total = sources.size)
        job = scope.launch {
            if (!append) {
                val saved = if (previous.isNotEmpty()) previous else loadShelf(key)
                if (token != generation) return@launch
                offers = saved
                val cachedItems = withContext(Dispatchers.Default) { UnifiedVod.sorted(UnifiedVod.merge(saved), filter.sort) }
                if (token != generation) return@launch
                _state.value = _state.value.copy(showingCache = saved.isNotEmpty(), items = cachedItems)
            }
            val permits = Semaphore(3)
            var completed = 0; var failed = 0; var hasMore = false
            try {
                log?.event("INFO", "VOD_BROWSE", "start round=$requestedRound sources=${sources.size} search=${query.isNotBlank()}")
                coroutineScope {
                    sources.map { source -> launch {
                        val result = permits.withPermit {
                            try {
                                withTimeout(12000) { fetch(source, requestedRound, filter, query, force) }
                            } catch (timeout: TimeoutCancellationException) { null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        }
                        if (token != generation) return@launch
                        completed++; if (result == null) failed++
                        if (result != null && result.page < result.pages) hasMore = true
                        if (result != null) offers = ((if (append) offers else offers.filterNot { it.source == source }) + result.movies.filter { UnifiedVod.matches(it, filter) }
                            .map { VodOffer(source, it.copy(episodes = emptyList())) }).distinctBy { it.source to it.movie.id }.take(1200)
                        val snapshot = offers; val revision = completed
                        val merged = withContext(Dispatchers.Default) { UnifiedVod.sorted(UnifiedVod.merge(snapshot), filter.sort) }
                        if (token == generation && revision >= _state.value.completed) _state.value = _state.value.copy(items = merged, completed = revision, showingCache = failed == completed && merged.isNotEmpty(),
                            failed = failed, canLoadMore = offers.size < 1200,
                            error = if (offers.size >= 1200) "本次已加载 1200 条记录，请使用分类或搜索缩小范围" else null)
                    } }.joinAll()
                }
                if (token == generation) log?.event("INFO", "VOD_BROWSE", "complete round=$requestedRound items=${offers.size} failed=$failed hasMore=$hasMore")
                if (token == generation && failed < sources.size) {
                    refreshedAt = System.currentTimeMillis()
                    saveShelf(key, offers)
                }
            } finally {
                if (token == generation) _state.value = _state.value.copy(loading = false,
                    canLoadMore = hasMore && offers.size < 1200,
                    error = _state.value.error ?: if (failed == sources.size && _state.value.items.isEmpty()) "暂时无法加载片单，请检查网络或稍后重试" else null,
                    notice = if (failed == sources.size && _state.value.items.isNotEmpty()) "当前显示上次浏览的片单，连接恢复后可刷新。"
                        else if (failed > 0 && _state.value.items.isNotEmpty()) "已显示当前可用片单，部分内容可稍后再刷新。" else null)
            }
        }
    }
    private suspend fun fetch(source: String, number: Int, filter: VodFilter, query: String, force: Boolean): CatalogPage {
        // Recent feed first: avoid waiting for taxonomy and rotating an arbitrary genre on entry.
        if (query.isBlank() && filter.genre == "全部" && filter.region == "全部" && filter.year == "全部") {
            val key = "recent|$number"
            val recent = (if (force) null else cached(source, key)) ?: client(source).load(source, number, "").also { save(source, key, it) }
            if (recent.movies.any { UnifiedVod.matches(it, filter) }) return recent
        }
        val sourceTypes = (if (force) null else types[source]) ?: run {
            val cached = if (force) null else cached(source, "types")
            val found = cached ?: try { client(source).load(source, 1, "", listingOnly = true).also { save(source, "types", it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { CatalogPage(emptyList(), 1, 1) }
            found.types.also { types[source] = it }
        }
        val ids = if (query.isBlank()) UnifiedVod.categoryIds(sourceTypes, filter.kind, filter.primaryGroup) else emptyList()
        val category = ids.takeIf { it.isNotEmpty() }?.let { it[(number - 1 + sources.indexOf(source).coerceAtLeast(0)) % it.size] }
        val page = if (ids.isNotEmpty()) (number - 1) / ids.size + 1 else number
        val key = "$category|$page|$query|${filter.year}|${filter.region}"
        val result = (if (force) null else cached(source, key)) ?: client(source).load(source, page, query, type = category, year = filter.year.takeIf { it.matches(Regex("\\d{4}")) }, area = filter.region.takeUnless { it in listOf("全部", "其他", "欧美", "日韩", "港台") }).also { save(source, key, it) }
        return result.copy(page = number, pages = (result.pages * ids.size.coerceAtLeast(1)).coerceAtMost(100000))
    }
    suspend fun details(selected: UnifiedMovie): UnifiedMovie = coroutineScope {
        val permits = Semaphore(4)
        val found = mutableListOf<VodOffer>()
        withTimeoutOrNull(9000) {
            sources.sortedBy { source -> selected.offers.none { it.source == source } }.map { source -> launch {
                permits.withPermit {
                    val results = try {
                        withTimeout(6000) {
                            val api = client(source)
                            val known = selected.offers.filter { it.source == source }
                            val movies = if (known.isNotEmpty()) known.take(2).flatMap { api.load(source, 1, "", it.movie.id).movies }
                                else api.load(source, 1, selected.movie.name).movies.take(30)
                            movies.filter { UnifiedVod.identity(it) == UnifiedVod.identity(selected.movie) }.map { VodOffer(source, it) }
                        }
                    } catch (timeout: TimeoutCancellationException) { emptyList() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                    found.addAll(results)
                }
            } }.joinAll()
        }
        if (found.isEmpty()) throw IllegalArgumentException("暂时未找到可用播放信息，请稍后重试")
        withContext(Dispatchers.Default) { UnifiedVod.merge(found.toList()).first() }
    }
    private suspend fun loadShelf(key: String): List<VodOffer> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(directory, "shelf-" + stableId(key) + ".json")
            if (!file.isFile || file.length() > 8 * 1024 * 1024) return@runCatching emptyList()
            val cached = json.decodeFromString<CachedShelf>(file.readText())
            if (System.currentTimeMillis() - cached.updated !in 0..604800000L) return@runCatching emptyList()
            cached.offers.filter { it.source in sources }.take(1200).map { VodOffer(it.source, it.movie.copy(episodes = emptyList())) }
        }.getOrDefault(emptyList())
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
