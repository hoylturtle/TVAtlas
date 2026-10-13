package com.tvatlas.player.epg

import com.tvatlas.core.epg.*
import com.tvatlas.core.model.Channel
import com.tvatlas.core.model.httpUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Serializable data class OfficialSupplement(val updated: Long, val name: String, val programmes: List<Programme>)
@Serializable data class CachedGuide(val source: String = "", val updated: Long = 0, val guide: Guide = Guide(),
    val supplements: Map<String, OfficialSupplement> = emptyMap())
data class GuideIndex(val channels: Map<String, List<Programme>>, val names: Map<String, String>) {
    companion object { fun build(guide: Guide) = GuideIndex(guide.programmes.groupBy { it.channel },
        guide.names.flatMap { (id, labels) -> labels.map { Guide.normalize(it) to id } }.toMap()) }
}
data class EpgState(val guide: Guide = Guide(), val loading: Boolean = false, val error: String? = null, val updated: Long = 0, val source: String = "", val officialIds: Set<String> = emptySet(), private val index: GuideIndex = GuideIndex.build(guide)) {
    private val byChannel get() = index.channels
    private val names get() = index.names
    fun entries(channel: Channel): List<Programme> {
        val id = channel.tvgId?.takeIf { it in byChannel } ?: byChannel.keys.firstOrNull { it.substringBefore('@') == channel.tvgId }
            ?: names[Guide.normalize(channel.name)] ?: channel.id.takeIf { it in byChannel }
        return byChannel[id].orEmpty()
    }
}
class EpgStore(private val file: File, private val scope: CoroutineScope, private val fetch: suspend (String) -> ByteArray) {
    private val _state = MutableStateFlow(EpgState()); val state = _state.asStateFlow()
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val requests = java.util.concurrent.atomic.AtomicLong()
    private var source = ""
    private var xmlUpdated = 0L
    private var base = Guide()
    private var supplements = emptyMap<String, OfficialSupplement>()
    fun needsRefresh() = System.currentTimeMillis() - xmlUpdated >= 21600000L
    fun hasOfficial(channel: Channel): Boolean = (channel.tvgId ?: channel.id) in _state.value.officialIds &&
        _state.value.entries(channel).any { it.end > System.currentTimeMillis() }
    private suspend fun publish(loading: Boolean = false, error: String? = null) {
        val now = System.currentTimeMillis()
        supplements = supplements.filterValues { now - it.updated < 3600000 && it.programmes.any { p -> p.end > now } }
        val values = supplements
        val combined = withContext(Dispatchers.Default) { EpgState(
            Guide(base.names + values.mapValues { listOf(it.value.name) },
                base.programmes.filter { it.channel !in values } + values.values.flatMap { it.programmes }),
            loading, error, maxOf(xmlUpdated, values.values.maxOfOrNull { it.updated } ?: 0), source, values.keys) }
        _state.value = combined
    }
    suspend fun load(url: String) {
        mutex.lock()
        try {
            val old = withContext(Dispatchers.IO) { runCatching { if (file.isFile && file.length() < 48 * 1024 * 1024)
                Json.decodeFromString<CachedGuide>(file.readText()) else null }.getOrNull() }
            if (old != null && old.source == url) {
                source = url; xmlUpdated = old.updated; base = old.guide; supplements = old.supplements; publish()
            }
        } finally { mutex.unlock() }
    }
    suspend fun reset(url: String) {
        requests.incrementAndGet(); mutex.lock()
        try { source = url; xmlUpdated = 0; base = Guide(); supplements = emptyMap(); publish() }
        finally { mutex.unlock() }
        load(url)
    }
    suspend fun refresh(url: String) {
        val token = requests.incrementAndGet()
        mutex.lock()
        try {
            if (token != requests.get()) return
            if (url.isBlank()) { base = Guide(); source = ""; xmlUpdated = 0; publish(); save(); return }
            require(httpUri(url) != null)
            _state.value = _state.value.copy(loading = true, error = null)
            val guide = withContext(Dispatchers.Default) { XmlTv.parse(fetch(url)) }
            if (token != requests.get()) return
            source = url; xmlUpdated = System.currentTimeMillis(); base = guide; publish(); save()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.value = _state.value.copy(loading = false, error = "节目单更新失败，已保留缓存") }
        finally { if (token == requests.get()) _state.value = _state.value.copy(loading = false); mutex.unlock() }
    }
    suspend fun official(channel: Channel, url: String, tvb: Boolean): Boolean {
        mutex.lock()
        try {
            val bytes = fetch(url)
            val items = withContext(Dispatchers.Default) { OfficialGuide.parse(bytes.toString(Charsets.UTF_8), channel.tvgId ?: channel.id, tvb) }
            if (items.isEmpty()) return false
            val id = channel.tvgId ?: channel.id
            supplements = supplements + (id to OfficialSupplement(System.currentTimeMillis(), channel.name, items))
            publish(); save(); return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        finally { mutex.unlock() }
    }
    private suspend fun save() = withContext(Dispatchers.IO) {
        runCatching { file.parentFile?.mkdirs(); val part = File(file.path + ".tmp")
            part.writeText(Json.encodeToString(CachedGuide(source, xmlUpdated, base, supplements)))
            if (!part.renameTo(file)) part.delete()
        }
    }
}
object OfficialGuide {
    private fun timestamp(value: String?, tvb: Boolean): Long? {
        if (value == null) return null
        value.toLongOrNull()?.takeIf { it > 1000000000 }?.let { return if (it < 100000000000L) it * 1000 else it }
        val normalized = value.replace("Z", "+0000").replace(Regex("([+-][0-9]{2}):([0-9]{2})$"), "$1$2")
        return listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd HH:mm:ss").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone(if (tvb) "Asia/Hong_Kong" else "UTC") }.parse(normalized)?.time }.getOrNull()
        }
    }
    fun parse(text: String, id: String, tvb: Boolean): List<Programme> {
        val root = Json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        val items = if (tvb) root.flatMap { group -> ((group as? JsonObject)?.get("item") as? JsonArray).orEmpty().flatMap { day ->
            ((day as? JsonObject)?.get("epg") as? JsonArray).orEmpty() } } else (root.firstOrNull() as? JsonArray).orEmpty()
        val programmes = items.take(3000).mapNotNull { item ->
            val p = item as? JsonObject ?: return@mapNotNull null
            fun field(name: String) = (p[name] as? JsonPrimitive)?.contentOrNull
            val start = timestamp(field(if (tvb) "start_datetime" else "start"), tvb) ?: return@mapNotNull null
            val title = (if (tvb) field("programme_title_tc") ?: field("programme_title_en") else field("name"))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Programme(id, start, if (tvb) 0 else timestamp(field("end"), false) ?: 0, title.take(1000))
        }.distinct().sortedBy { it.start }
        return programmes.mapIndexed { i, p -> p.copy(end = p.end.takeIf { it > p.start } ?: programmes.getOrNull(i + 1)?.start ?: p.start + 1800000) }
    }
}
