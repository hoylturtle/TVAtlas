package com.tvatlas.core.vod

/** Match the same episode across provider lines; never combine different numbered episodes. */
data class VodEpisodeChoice(val name: String, val candidates: List<CatalogEpisode>)
object VodEpisodeChoices {
    fun of(movie: CatalogMovie): List<VodEpisodeChoice> {
        val groups = linkedMapOf<String, MutableList<CatalogEpisode>>()
        movie.episodes.forEach { episode -> groups.getOrPut(key(episode.name)) { mutableListOf() }.add(episode) }
        return groups.entries.sortedBy { it.key.removePrefix("episode:").toLongOrNull() ?: Long.MAX_VALUE }.map { (key, episodes) -> VodEpisodeChoice(if (key == "feature") "正片" else episodes.first().name, episodes.sortedByDescending { it.qualityHeight }.distinctBy { it.url }) }
    }
    fun identity(name: String): String = key(name)
    private fun key(name: String): String {
        val compact = name.lowercase().replace(Regex("\\s+"), "")
        val numbered = Regex("(?:第)?0*(\\d+)(?:集|话|話|期)?").matchEntire(compact)
            ?: Regex("(?:ep?|episode)0*(\\d+)").matchEntire(compact)
        if (numbered != null) return "episode:${numbered.groupValues[1].trimStart('0').ifBlank { "0" }}"
        if (compact in setOf("正片", "全集", "播放", "hd", "hd中字", "高清", "蓝光", "1080p", "720p", "4k")) return "feature"
        return "label:$compact"
    }
}
