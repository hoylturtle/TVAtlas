package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable internal data class HomeVodReference(val source: String, val movieId: String)
@Serializable internal data class HomeRecentVod(val movie: CatalogMovie, val references: List<HomeVodReference>, val episode: String, val at: Long) {
    fun unified(): UnifiedMovie = UnifiedMovie(movie.id, movie, references.map { VodOffer(it.source, movie.copy(id = it.movieId)) })
}

/** Local metadata for the home shelf; never persist media URLs or episode lists. */
internal class HomeHistoryStore(private val file: File, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<List<HomeRecentVod>>(emptyList())
    val state = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val loaded = scope.async(Dispatchers.IO) {
        _state.value = runCatching {
            if (!file.isFile || file.length() > 1024 * 1024) emptyList() else
                json.decodeFromString<List<HomeRecentVod>>(file.readText()).take(12)
        }.getOrDefault(emptyList())
    }
    fun record(item: UnifiedMovie, episode: String): Job = scope.launch {
        loaded.await()
        mutex.withLock {
            val entry = HomeRecentVod(item.movie.copy(episodes = emptyList()),
                item.offers.take(12).map { HomeVodReference(it.source, it.movie.id) }, episode.take(80), System.currentTimeMillis())
            _state.value = (listOf(entry) + _state.value.filterNot { it.movie.id == entry.movie.id }).take(12)
            withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val part = File(file.path + ".tmp")
                    part.writeText(json.encodeToString(_state.value))
                    if (!part.renameTo(file)) part.delete()
                }
            }
        }
    }
}
