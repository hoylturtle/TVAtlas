package com.tvatlas.player.browse

import com.tvatlas.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable private data class BrowseCacheFile(val version: Int, val entries: Map<String, ChannelBrowseMetadata>)
internal data class CachedBrowseResult(val index: ChannelBrowseIndex, val reused: Int, val calculated: Int, val diskRead: Boolean, val saved: Boolean)

/** Lazy app-private advisory cache. A failed read/write never prevents browsing. */
internal class ChannelBrowseCache(private val file: File, private val ruleVersion: Int = ChannelBrowseIndex.RULE_VERSION) {
    private data class Snapshot(val channels: List<Channel>, val result: CachedBrowseResult)
    @Volatile private var snapshot: Snapshot? = null
    fun peek(channels: List<Channel>): ChannelBrowseIndex? = snapshot?.takeIf { it.channels == channels }?.result?.index
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private var entries = emptyMap<String, ChannelBrowseMetadata>()
    private var persisted: Map<String, ChannelBrowseMetadata>? = null

    suspend fun get(channels: List<Channel>): CachedBrowseResult = mutex.withLock {
        snapshot?.takeIf { it.channels == channels }?.let { return@withLock it.result.copy(diskRead = false, saved = false) }
        var diskRead = false
        if (!loaded) {
            entries = withContext(Dispatchers.IO) {
                try {
                    if (!file.isFile || file.length() > MAX_BYTES) emptyMap() else {
                        val saved = json.decodeFromString<BrowseCacheFile>(file.readText())
                        if (saved.version != ruleVersion || saved.entries.size > MAX_ENTRIES) emptyMap()
                        else { diskRead = true; saved.entries }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyMap() }
            }
            if (diskRead) persisted = entries
            loaded = true
        }
        val built = withContext(Dispatchers.Default) { ChannelBrowseIndex.update(channels, entries) }
        if (channels.isNotEmpty()) entries = built.entries
        // An empty transient library at startup must not erase a useful previous cache.
        val shouldSave = channels.isNotEmpty() && entries.isNotEmpty() && entries.size <= MAX_ENTRIES && entries != persisted
        var saved = false
        if (shouldSave) saved = withContext(Dispatchers.IO) {
            val part = File(file.path + ".tmp")
            try {
                val bytes = json.encodeToString(BrowseCacheFile(ruleVersion, entries)).toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) false else {
                    file.parentFile?.mkdirs()
                    part.outputStream().use { it.write(bytes) }
                    part.renameTo(file)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { part.delete() }
        }
        if (saved) persisted = entries
        CachedBrowseResult(built.index, built.reused, built.calculated, diskRead, saved).also {
            snapshot = Snapshot(channels.toList(), it.copy(reused = channels.size, calculated = 0))
        }
    }

    companion object { private const val MAX_BYTES = 8 * 1024 * 1024; private const val MAX_ENTRIES = 30000 }
}
