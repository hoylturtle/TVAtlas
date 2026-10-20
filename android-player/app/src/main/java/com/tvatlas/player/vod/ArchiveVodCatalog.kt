package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import com.tvatlas.player.network.readCancellable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Public-domain classic films/television only. Archive upload dates are never release dates. */
internal object ArchiveVodCatalog {
    const val SOURCE = "https://archive.org/advancedsearch.php"
    private val types = listOf(CatalogType("film", "电影"), CatalogType("television", "欧美剧"))
    private val json = Json { ignoreUnknownKeys = true }
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun television(row: JsonObject): Boolean = (row["collection"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "classic_tv" } == true || row.text("collection") == "classic_tv"
    private suspend fun read(client: OkHttpClient, url: String): JsonObject =
        client.newCall(Request.Builder().url(url).header("User-Agent", "TVAtlas public-domain catalogue").build()).readCancellable { response ->
            require(response.isSuccessful) { "公共影视目录返回 HTTP ${response.code}" }
            val input = requireNotNull(response.body).byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= 4 * 1024 * 1024); out.write(buffer, 0, count) }
            val bytes = out.toByteArray()
            require(bytes.size <= 4 * 1024 * 1024) { "公共影视目录响应过大" }
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
    private fun allowedLicense(value: String): Boolean = value.trimEnd('/') in setOf(
        "http://creativecommons.org/licenses/publicdomain", "https://creativecommons.org/licenses/publicdomain",
        "http://creativecommons.org/publicdomain/mark/1.0", "https://creativecommons.org/publicdomain/mark/1.0")
    suspend fun load(client: OkHttpClient, page: Int, search: String, id: String?, type: String?, listingOnly: Boolean, year: String?, area: String?, request: suspend (String) -> JsonObject = { read(client, it) }): CatalogPage {
        if (listingOnly) return CatalogPage(emptyList(), 1, 1, types)
        if (year?.toIntOrNull()?.let { it !in 1888..1969 } == true || area != null && area !in listOf("美国", "英国"))
            return CatalogPage(emptyList(), page, page, types)
        if (id != null) {
            require(id.matches(Regex("[A-Za-z0-9_.-]{1,200}")))
            val root = request("https://archive.org/metadata/$id")
            val metadata = root["metadata"] as? JsonObject ?: error("公共影视条目不存在")
            require(allowedLicense(metadata.text("licenseurl"))) { "此条目没有公共领域标记" }
            val base = parseMovie(metadata, id, television(metadata))
            val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val byName = files.associateBy { it.text("name") }
            fun original(file: JsonObject): String {
                var current = file
                repeat(4) { val parent = current.text("original"); if (parent.isBlank()) return current.text("name")
                    current = byName[parent] ?: return parent }
                return current.text("name")
            }
            val groups = files.filter { it.text("name").endsWith(".mp4", true) && it.text("name").length <= 500 }
                .groupBy(::original).entries.take(40)
            val episodes = groups.flatMap { (name, variants) ->
                variants.sortedBy { it.text("size").toLongOrNull() ?: Long.MAX_VALUE }.take(3).map { file ->
                    val url = "https://archive.org/download/".toHttpUrl().newBuilder().addPathSegment(id).apply {
                        file.text("name").split('/').forEach { addPathSegment(it) }
                    }.build().toString()
                    CatalogEpisode(if (groups.size == 1) "正片" else name.substringBeforeLast('.').take(80), url, "Internet Archive · 公共领域")
                }
            }
            return CatalogPage(listOf(base.copy(episodes = episodes)), 1, 1, types)
        }
        // Limit to classic collections and explicit public-domain labels; do not infer rights from HTTP 200.
        val collection = when (type) { "film" -> "feature_films"; "television" -> "classic_tv"; else -> "(feature_films OR classic_tv)" }
        val query = buildString {
            append("mediatype:movies AND collection:$collection AND year:[1888 TO 1969] AND (")
            append("licenseurl:\"http://creativecommons.org/licenses/publicdomain/\" OR ")
            append("licenseurl:\"http://creativecommons.org/publicdomain/mark/1.0/\" OR ")
            append("licenseurl:\"https://creativecommons.org/publicdomain/mark/1.0/\")")
            if (year != null) append(" AND year:$year")
            if (search.isNotBlank()) append(" AND title:\"").append(search.take(100).replace(Regex("[\\\"\\\\]"), " ")).append('"')
        }
        val url = SOURCE.toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("output", "json")
            .addQueryParameter("rows", "20").addQueryParameter("page", page.toString()).addQueryParameter("sort[]", "year desc").apply {
                listOf("identifier", "title", "year", "description", "collection", "licenseurl").forEach { addQueryParameter("fl[]", it) }
            }.build()
        val response = request(url.toString())["response"] as? JsonObject ?: error("公共影视目录格式异常")
        val movies = (response["docs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { allowedLicense(it.text("licenseurl")) }.mapNotNull { row ->
                val identifier = row.text("identifier")
                if (!identifier.matches(Regex("[A-Za-z0-9_.-]{1,200}"))) null else
                    parseMovie(row, identifier, television(row))
            }
        val count = response.text("numFound").toIntOrNull() ?: 0
        return CatalogPage(movies, page, ((count + 19) / 20).coerceAtLeast(1), types)
    }
    private fun parseMovie(row: JsonObject, id: String, television: Boolean): CatalogMovie = CatalogMovie(
        id, row.text("title").ifBlank { id }.take(200), if (television) "欧美剧" else "电影", emptyList(),
        "https://archive.org/services/img/$id", "Internet Archive · 公共领域经典作品。" + row.text("description").replace(Regex("<[^>]*>"), " ").take(1200),
        row.text("year").takeIf { it.toIntOrNull()?.let { number -> number in 1888..1969 } == true }.orEmpty(), "", typeId = if (television) "television" else "film")
}
