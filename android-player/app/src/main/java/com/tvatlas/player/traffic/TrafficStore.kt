package com.tvatlas.player.traffic

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable data class TrafficUsage(val id: String, val name: String, val proxy: Boolean,
    val received: Long = 0, val sent: Long = 0) {
    val total: Long get() = received + sent
}
@Serializable data class TrafficSnapshot(val since: Long = System.currentTimeMillis(), val entries: List<TrafficUsage> = emptyList()) {
    val direct: Long get() = entries.filter { !it.proxy }.sumOf { it.total }
    val proxy: Long get() = entries.filter { it.proxy }.sumOf { it.total }
}

/** Count on network threads, publish once a second, and batch atomic disk writes off the UI thread. */
class TrafficStore(private val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = linkedMapOf<String, TrafficUsage>()
    private var since = System.currentTimeMillis()
    private var revision = 0L
    private var savedRevision = -1L
    private var resetBeforeLoad = false
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(TrafficSnapshot(since))
    val state = _state.asStateFlow()
    private val loaded = scope.launch {
        val old = runCatching { if (file.isFile && file.length() <= 2_000_000) json.decodeFromString<TrafficSnapshot>(file.readText()) else null }.getOrNull()
        synchronized(lock) {
            if (!resetBeforeLoad && old != null) {
                since = old.since
                old.entries.filter { it.received >= 0 && it.sent >= 0 }.forEach { item ->
                    val current = entries[item.id]
                    entries[item.id] = item.copy(name = current?.name ?: item.name,
                        received = item.received + (current?.received ?: 0), sent = item.sent + (current?.sent ?: 0))
                }
            }
            _state.value = snapshot()
        }
    }
    private val ticker = scope.launch {
        loaded.join()
        var ticks = 0
        while (isActive) {
            delay(1000)
            synchronized(lock) { _state.value = snapshot() }
            if (++ticks % 5 == 0) persist()
        }
    }
    private fun snapshot() = TrafficSnapshot(since, entries.values.toList())
    fun record(id: String, name: String, proxy: Boolean, received: Long = 0, sent: Long = 0) {
        if (received <= 0 && sent <= 0) return
        synchronized(lock) {
            val old = entries[id] ?: TrafficUsage(id, name, proxy)
            entries[id] = old.copy(name = name, received = old.received + received.coerceAtLeast(0), sent = old.sent + sent.coerceAtLeast(0))
            revision++
        }
    }
    fun reset() {
        synchronized(lock) {
            resetBeforeLoad = true; entries.clear(); since = System.currentTimeMillis(); revision++
            _state.value = snapshot()
        }
        scope.launch { loaded.join(); persist() }
    }
    private val diskLock = kotlinx.coroutines.sync.Mutex()
    fun checkpoint() { scope.launch { flush() } }
    suspend fun flush() { loaded.join(); persist() }
    private suspend fun persist() {
        diskLock.lock()
        try {
            val (value, token) = synchronized(lock) { snapshot() to revision }
            if (token == savedRevision) return
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val part = File(file.path + ".tmp")
                part.writeText(json.encodeToString(value))
                if (!part.renameTo(file)) throw java.io.IOException("Traffic statistics could not be saved")
            }
            savedRevision = token
        } catch (_: java.io.IOException) { /* Retry the unsaved revision on the next batch. */ }
        finally { diskLock.unlock() }
    }
    suspend fun shutdown() { ticker.cancelAndJoin(); flush(); scope.cancel() }
    fun close() { scope.launch { shutdown() } }
}
