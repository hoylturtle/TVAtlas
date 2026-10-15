package com.tvatlas.player.playback

import com.tvatlas.core.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Bounded local observations, no media URLs, tokens or exception text. Disk access stays off the UI thread. */
class RouteReliabilityStore(private val file: File) {
    private val lock = Mutex()
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<List<RouteObservation>>(emptyList())
    val state = _state.asStateFlow()
    suspend fun load(): List<RouteObservation> = lock.withLock { withContext(Dispatchers.IO) { read(); _state.value } }
    private fun read() {
        if (loaded) return
        if (file.isFile) {
            require(file.length() <= 1_000_000)
            _state.value = json.decodeFromString<List<RouteObservation>>(file.readText()).takeLast(2000)
        }
        loaded = true
    }
    suspend fun record(event: RouteObservation) = lock.withLock { withContext(Dispatchers.IO) {
        read()
        val previous = if (event.attemptId == null) _state.value else _state.value.filterNot { it.attemptId == event.attemptId }
        val next = (previous + event).filter { it.at in (event.at - RouteReliability.WINDOW_MS)..event.at }.takeLast(2000)
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(json.encodeToString(next))
        check(temporary.renameTo(file))
        _state.value = next
    } }
}
