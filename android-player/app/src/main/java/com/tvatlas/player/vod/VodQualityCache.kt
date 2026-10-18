package com.tvatlas.player.vod

import com.tvatlas.core.model.stableId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable internal data class MediaQuality(val height: Int, val checkedAt: Long, val latencyMs: Long, val actual: Boolean, val playable: Boolean = true)

/** Only opaque URL+route keys and metadata; no video bytes, URLs or credentials on disk. */
internal class VodQualityCache(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var loaded = false
    private var entries = emptyMap<String, MediaQuality>()
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun read() = withContext(Dispatchers.IO) {
        if (!loaded) {
            entries = runCatching { if (!file.isFile || file.length() > 128_000) emptyMap() else
                json.decodeFromString<Map<String, MediaQuality>>(file.readText()).filter { (key, value) ->
                    key.matches(Regex("[a-f0-9]{64}")) && value.height in 0..8640 && value.latencyMs in 0..60_000 &&
                        now() - value.checkedAt in 0..300_000
                }.entries.sortedByDescending { it.value.checkedAt }.take(128).associate { it.toPair() }
            }.getOrDefault(emptyMap())
            loaded = true
        }
    }
    suspend fun get(url: String, context: String): MediaQuality? = mutex.withLock {
        read(); entries[key(url, context)]?.takeIf { now() - it.checkedAt in 0..300_000 }
    }
    suspend fun record(url: String, context: String, height: Int, latencyMs: Long, actual: Boolean = false) = mutex.withLock {
        if (height !in 0..8640) return@withLock
        read()
        val id = key(url, context)
        val previous = entries[id]?.takeIf { now() - it.checkedAt in 0..300_000 }
        val measured = if (previous?.actual == true && !actual) previous.copy(checkedAt = now(), latencyMs = latencyMs.coerceIn(0, 60_000))
            else MediaQuality(height, now(), latencyMs.coerceIn(0, 60_000), actual)
        entries = (entries.filterValues { now() - it.checkedAt in 0..300_000 } + (id to measured))
            .entries.sortedByDescending { it.value.checkedAt }.take(128).associate { it.toPair() }
        save()
    }
    suspend fun reject(url: String, context: String) = mutex.withLock {
        read(); entries = (entries.filterValues { now() - it.checkedAt in 0..300_000 } +
            (key(url, context) to MediaQuality(0, now(), 0, false, playable = false)))
            .entries.sortedByDescending { it.value.checkedAt }.take(128).associate { it.toPair() }; save()
    }
    suspend fun invalidate(url: String, context: String) = mutex.withLock { read(); entries = entries - key(url, context); save() }
    private fun key(url: String, context: String) = stableId("$url\u0000$context")
    private suspend fun save() = withContext(Dispatchers.IO) {
        runCatching { file.parentFile?.mkdirs(); val part = File(file.path + ".tmp")
            part.writeText(json.encodeToString(entries)); if (!part.renameTo(file)) part.delete() }
    }
}
