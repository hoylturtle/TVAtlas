package com.tvatlas.core.vod.stremio

import com.tvatlas.core.model.httpUri
import com.tvatlas.core.model.stableId
import com.tvatlas.core.vod.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class AddonResource(val name: String, val types: List<String>, val prefixes: List<String> = emptyList())
@Serializable data class AddonCatalog(val id: String, val type: String, val search: Boolean = false, val skip: Boolean = false,
    val required: List<String> = emptyList())
@Serializable data class AddonManifest(val id: String, val name: String, val resources: List<AddonResource>, val catalogs: List<AddonCatalog>,
    val configurationRequired: Boolean = false) {
    fun supports(resource: String, type: String, id: String) = resources.any { it.name == resource && type in it.types && (it.prefixes.isEmpty() || it.prefixes.any(id::startsWith)) }
    val capability: String get() = if (resources.any { it.name == "stream" }) "提供播放线路 · 广告情况未知" else "仅片目信息 · 不提供播放线路"
}
data class AddonStreams(val episodes: List<CatalogEpisode>, val unsupported: Int)

/** Data-only HTTP protocol. No addon code, web pages, magnets or event callbacks are executed. */
object StremioProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    fun root(text: String): JsonObject {
        require(text.length <= 4 * 1024 * 1024) { "插件响应过大" }
        return try { json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("插件未返回 JSON 对象") }
        catch (_: kotlinx.serialization.SerializationException) { throw IllegalArgumentException("插件 JSON 格式无效") }
    }
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(100)
    private fun safeId(value: String) = value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }
    fun manifest(text: String): AddonManifest {
        val row = root(text)
        require(safeId(row.text("id")) && row.text("name").isNotBlank()) { "插件清单缺少名称或 ID" }
        val types = row.strings("types").filter { it in setOf("movie", "series") }
        val prefixes = row.strings("idPrefixes")
        val resources = (row["resources"] as? JsonArray).orEmpty().take(30).mapNotNull {
            when (it) {
                is JsonPrimitive -> AddonResource(it.content, types, prefixes)
                is JsonObject -> AddonResource(it.text("name"), it.strings("types"), it.strings("idPrefixes"))
                else -> null
            }
        }.filter { it.name in setOf("catalog", "meta", "stream", "subtitles") }
        require(resources.isNotEmpty()) { "插件不提供可兼容的影视接口" }
        val catalogs = (row["catalogs"] as? JsonArray).orEmpty().take(30).mapNotNull {
            val c = it as? JsonObject ?: return@mapNotNull null
            if (!safeId(c.text("id")) || c.text("type") !in types) return@mapNotNull null
            val extra = (c["extra"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val names = extra.map { it.text("name") } + c.strings("extraSupported")
            AddonCatalog(c.text("id"), c.text("type"), "search" in names, "skip" in names,
                (extra.filter { (it["isRequired"] as? JsonPrimitive)?.booleanOrNull == true }.map { it.text("name") } + c.strings("extraRequired")).distinct())
        }
        return AddonManifest(row.text("id"), row.text("name").replace(Regex("[\\p{Cntrl}]"), " ").take(100), resources, catalogs,
            ((row["behaviorHints"] as? JsonObject)?.get("configurationRequired") as? JsonPrimitive)?.booleanOrNull == true)
    }
    fun movie(row: JsonObject, fallbackType: String, namespace: String = ""): CatalogMovie? {
        val id = row.text("id"); val type = row.text("type").ifBlank { fallbackType }; val name = row.text("name").trim()
        if (!safeId(id) || type !in setOf("movie", "series") || name.isBlank()) return null
        val genres = row.strings("genres").joinToString(" ") { genreNames[it] ?: it }
        val year = Regex("(?:18|19|20)\\d{2}").find(row.text("releaseInfo").ifBlank { row.text("year") }.ifBlank { row.text("released") })?.value.orEmpty()
        val imdb = row.text("imdb_id").ifBlank { id }.takeIf { Regex("tt\\d{5,12}").matches(it) }.orEmpty()
        return CatalogMovie("$type:$id", name.replace(Regex("[\\p{Cntrl}]"), " ").take(80), if (type == "movie") "电影" else "剧集", emptyList(),
            row.text("poster").takeIf { it.length <= 4096 && httpUri(it) != null }, row.text("description").replace(Regex("<[^>]*>"), " ").take(1500), year,
            row.text("country").split(',').joinToString(",") { countryNames[it.trim()] ?: it.trim() }.take(80), genres, type, imdbId = imdb, discoveryOnly = true, identityNamespace = namespace)
    }
    fun catalog(text: String, type: String, source: String = ""): List<CatalogMovie> {
        val root = root(text)
        require(root["metas"] is JsonArray) { "插件没有返回片单" }
        return root.getValue("metas").jsonArray.take(100).mapNotNull { (it as? JsonObject)?.let { row -> movie(row, type, source) } }.distinctBy { it.id }
    }
    fun pendingEpisode(source: String, streamId: String, name: String) =
        CatalogEpisode(name, "stremio://${stableId(source)}/${stableId(streamId)}", "待查询播放线路", streamId = streamId)
    fun detail(text: String, source: String, type: String): CatalogMovie {
        val row = root(text)["meta"] as? JsonObject ?: error("插件没有返回影片资料")
        val base = movie(row, type, source) ?: error("插件影片资料无效")
        fun pending(id: String, name: String) = pendingEpisode(source, "$type:$id", name)
        val episodes = if (type == "movie") listOf(pending(row.text("id"), "正片")) else
            (row["videos"] as? JsonArray).orEmpty().take(2000).mapNotNull {
                val video = it as? JsonObject ?: return@mapNotNull null
                val id = video.text("id"); val season = video.text("season").toIntOrNull(); val episode = video.text("episode").toIntOrNull()
                if (!safeId(id) || season == null || episode == null || season !in 0..999 || episode !in 0..99999) return@mapNotNull null
                pending(id, "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}")
            }.distinctBy { it.streamId }
        return base.copy(episodes = episodes)
    }
    fun streams(text: String, name: String): AddonStreams {
        val root = root(text); require(root["streams"] is JsonArray) { "插件没有返回播放线路" }
        var unsupported = 0
        val episodes = root.getValue("streams").jsonArray.take(100).mapNotNull {
            val row = it as? JsonObject ?: return@mapNotNull null
            val url = row.text("url")
            if (url.length > 8192 || httpUri(url) == null || listOf("infoHash", "ytId", "nzbUrl", "rarUrls", "zipUrls", "7zipUrls", "tgzUrls", "tarUrls").any(row::containsKey)) { unsupported++; return@mapNotNull null }
            val hints = row["behaviorHints"] as? JsonObject
            val requestHeaders = (hints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject
            val headers = requestHeaders.orEmpty().entries.associate { (key, value) -> key to ((value as? JsonPrimitive)?.contentOrNull ?: "") }
            if (headers.size > 8 || headers.any { (k,v) -> k.lowercase() !in setOf("user-agent", "referer", "origin", "authorization", "cookie") || v.length > 4096 || v.any { c -> c.isISOControl() } }) { unsupported++; return@mapNotNull null }
            val label = row.text("name").ifBlank { "HTTP 视频线路" }.replace(Regex("[\\p{Cntrl}]"), " ").take(80)
            CatalogEpisode(name, url, label, VodQuality.height(label + " " + row.text("description")), headers = headers)
        }.distinctBy { it.url }
        return AddonStreams(episodes, unsupported)
    }
    private val genreNames = mapOf("Action" to "动作", "Comedy" to "喜剧", "Drama" to "剧情", "Romance" to "爱情", "Sci-Fi" to "科幻", "Horror" to "恐怖", "Mystery" to "悬疑", "War" to "战争", "Documentary" to "纪录", "Animation" to "动画", "Crime" to "犯罪", "Fantasy" to "奇幻", "Adventure" to "冒险")
    private val countryNames = mapOf("United States" to "美国", "USA" to "美国", "United Kingdom" to "英国", "UK" to "英国", "Japan" to "日本", "South Korea" to "韩国", "China" to "大陆", "Hong Kong" to "香港", "Taiwan" to "台湾")
}
