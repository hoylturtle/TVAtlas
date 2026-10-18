package com.tvatlas.player.vod

import com.tvatlas.core.model.stableId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable internal data class SourceHealth(val failures: Int = 0, val level: Int = 0,
    val retryAt: Long = 0, val updated: Long = 0, val latencyMs: Long? = null)

/** Lazy, bounded, route-specific circuit breaker. Cancellations and offline policy failures are not outcomes. */
internal class VodSourceHealth(private val file: File, scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    val state = _state.asStateFlow()
    private val loaded = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            _state.value = runCatching {
                if (!file.isFile || file.length() > 256_000) emptyMap() else
                    json.decodeFromString<Map<String, SourceHealth>>(file.readText()).filter { (key, value) ->
                        key.matches(Regex("[a-f0-9]{64}")) && value.updated in (now() - 7 * DAY)..now() &&
                            value.failures in 0..100 && value.level in 0..3 && value.retryAt <= now() + DAY
                    }.entries.sortedByDescending { it.value.updated }.take(200).associate { it.toPair() }
            }.getOrDefault(emptyMap())
        }
    }
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun key(source: String, context: String): String {
        val raw = "$source\u0000$context"
        return keys[raw] ?: stableId(raw).also { if (keys.size >= 256) keys.clear(); keys.putIfAbsent(raw, it) }
    }
    fun get(source: String, context: String): SourceHealth? = _state.value[key(source, context)]
    suspend fun eligible(source: String, context: String): Boolean { loaded.join(); return (get(source, context)?.retryAt ?: 0) <= now() }
    suspend fun record(source: String, context: String, success: Boolean, latencyMs: Long? = null) {
        loaded.join()
        mutex.withLock {
            val id = key(source, context); val time = now(); val old = _state.value[id] ?: SourceHealth()
            val streak = if (success) 0 else if (old.retryAt in 1..time) 1 else (old.failures + 1).coerceAtMost(100)
            val level = if (success) (old.level - 1).coerceAtLeast(0) else if (streak >= 2) (old.level + 1).coerceAtMost(3) else old.level
            val retry = if (success || streak < 2) 0 else time + listOf(30 * MINUTE, 2 * 60 * MINUTE, DAY)[(level - 1).coerceAtLeast(0)]
            val value = SourceHealth(streak, level, retry, time, if (success) latencyMs else old.latencyMs)
            _state.value = (_state.value.filterValues { it.updated >= time - 7 * DAY } + (id to value))
                .entries.sortedByDescending { it.value.updated }.take(200).associate { it.toPair() }
            persist()
        }
    }
    suspend fun reset(source: String, context: String) {
        loaded.join(); mutex.withLock { _state.value = _state.value - key(source, context); persist() }
    }
    private suspend fun persist() = withContext(Dispatchers.IO) {
        // History is advisory: disk failure must never abort a successful catalogue response.
        runCatching { file.parentFile?.mkdirs(); val part = File(file.path + ".tmp")
            part.writeText(json.encodeToString(_state.value)); if (!part.renameTo(file)) part.delete() }
    }
    companion object { private const val MINUTE = 60_000L; private const val DAY = 86_400_000L }
}
