package com.tvatlas.core.vod.stremio

import kotlinx.serialization.json.*

/** Small curated CC-BY catalogue, bundled protocol data rather than executable addon code. */
object OpenFilmsAddon {
    const val ADDRESS = "builtin:open-films"
    val manifest = StremioProtocol.manifest("""{"id":"com.tvatlas.openfilms","name":"开放短片 · Blender","resources":["catalog","meta","stream"],"types":["movie"],"catalogs":[{"id":"open-films","type":"movie","extra":[{"name":"search"}]}]}""")
    private data class Film(val id: String, val title: String, val english: String, val year: String, val imdb: String, val archive: String, val filename: String, val license: String)
    private val films = listOf(
        Film("big-buck-bunny", "大雄兔", "Big Buck Bunny", "2008", "tt1254207", "BigBuckBunny_328", "BigBuckBunny_512kb.mp4", "https://creativecommons.org/licenses/by/3.0/us/"),
        Film("sintel", "辛特尔", "Sintel", "2010", "tt1727587", "Sintel", "sintel-2048-stereo.mp4", "https://creativecommons.org/licenses/by/3.0/")
    )
    fun response(resource: String, type: String, id: String, search: String = ""): String {
        require(type == "movie")
        fun meta(f: Film) = buildJsonObject {
            put("id", f.id); put("type", "movie"); put("name", "${f.title} · ${f.english}"); put("year", f.year); put("imdb_id", f.imdb)
            put("poster", "https://archive.org/services/img/${f.archive}")
            put("description", "Blender Foundation 开放短片，${f.year}。授权：${f.license}。视频由 Internet Archive 存档提供；并非新上映商业影片。")
        }
        return when (resource) {
            "catalog" -> buildJsonObject { putJsonArray("metas") { films.filter { search.isBlank() || "${it.title} ${it.english}".contains(search, true) }.forEach { add(meta(it)) } } }
            "meta" -> buildJsonObject { put("meta", meta(films.single { it.id == id })) }
            "stream" -> buildJsonObject { putJsonArray("streams") { films.find { it.id == id }?.let { f -> add(buildJsonObject {
                put("name", "Internet Archive · 开放短片"); put("url", "https://archive.org/download/${f.archive}/${f.filename}")
            }) } } }
            else -> error("Unsupported bundled resource")
        }.toString()
    }
}
