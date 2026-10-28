package com.tvatlas.core.vod

import com.tvatlas.core.model.httpUri
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

@Serializable data class CatalogEpisode(val name: String, val url: String, val line: String, val qualityHeight: Int = 0, val streamId: String = "", val headers: Map<String, String> = emptyMap())
@Serializable data class CatalogMovie(val id: String, val name: String, val category: String, val episodes: List<CatalogEpisode>,
    val poster: String? = null, val summary: String = "",
    val year: String = "", val area: String = "", val genres: String = "", val typeId: String = "", val updatedAt: Long = 0, val imdbId: String = "", val discoveryOnly: Boolean = false, val identityNamespace: String = "")
@Serializable data class CatalogType(val id: String, val name: String)
@Serializable data class CatalogPage(val movies: List<CatalogMovie>, val page: Int, val pages: Int, val types: List<CatalogType> = emptyList())
object VodCatalog {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun updated(value: String): Long {
        val epoch = value.toLongOrNull()?.let { if (it < 100000000000L) it * 1000 else it }
        val date = epoch ?: runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).apply {
                isLenient = false; timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            }.parse(value)?.time
        }.getOrNull() ?: 0L
        return date.takeIf { it in 1..(System.currentTimeMillis() + 86400000L) } ?: 0L
    }
    fun parse(text: String): CatalogPage {
        require(text.length <= 4 * 1024 * 1024) { "片单响应过大" }
        val root = json.parseToJsonElement(text).jsonObject
        fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(root["list"] is JsonArray) { "接口没有返回可识别的 JSON 片单" }
        val movies = root.getValue("list").jsonArray.take(100).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("vod_id"); val name = item.str("vod_name").take(80)
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val lines = item.str("vod_play_from").split("$$$")
            val episodes = item.str("vod_play_url").split("$$$").take(16).flatMapIndexed { index, group ->
                group.split('#').take(500).mapNotNull { entry ->
                    val split = entry.indexOf('$')
                    val url = (if (split >= 0) entry.substring(split + 1) else entry).trim()
                    val uri = httpUri(url) ?: return@mapNotNull null
                    // Only native media URLs; webpage parser services are not executed.
                    if (!Regex("(?i)\\.(m3u8|mp4|mkv|mpd|webm|ts)$").containsMatchIn(uri.path.orEmpty())) return@mapNotNull null
                    CatalogEpisode((if (split >= 0) entry.substring(0, split) else "播放").take(80), url,
                        lines.getOrElse(index) { "线路 ${index + 1}" }.take(80),
                        VodQuality.height(item.str("vod_remarks") + " " + item.str("vod_version") + " " + entry.substringBefore('$') + " " + lines.getOrElse(index) { "" }))
                }
            }
            CatalogMovie(id, name, item.str("type_name").take(80), episodes,
                item.str("vod_pic").trim().takeIf { it.length <= 4096 && httpUri(it) != null },
                item.str("vod_content").take(10000).replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ").trim().take(1500),
                item.str("vod_year").take(20), item.str("vod_area").take(80), item.str("vod_class").take(160), item.str("type_id").take(30), updated(item.str("vod_time").ifBlank { item.str("vod_time_add") }))
        }.distinctBy { it.id }
        return CatalogPage(movies, root.str("page").toIntOrNull()?.coerceAtLeast(1) ?: 1,
            root.str("pagecount").toIntOrNull()?.coerceIn(1, 100000) ?: 1,
            (root["class"] as? JsonArray).orEmpty().mapNotNull { element ->
                val type = element as? JsonObject ?: return@mapNotNull null
                val id = type.str("type_id"); val name = type.str("type_name")
                if (id.isBlank() || name.isBlank()) null else CatalogType(id.take(30), name.take(80))
            }.take(200))
    }
}
