package com.tvatlas.player.source

import com.tvatlas.core.model.httpUri

/** Explicit official broadcaster endpoints; never accept arbitrary Akamai or third-party hosts. */
object HongKongPublicSources {
    const val RTHK_PAGE = "https://www.rthk.hk/tv/"
    const val HOY_PAGE = "https://hoy.tv/live"
    private val rthk = listOf(
        Triple("RTHK TV 31", "RTHKTV31.hk", "https://www.rthk.hk/feeds/dtt/rthktv31_https.m3u8"),
        Triple("RTHK TV 32", "RTHKTV32.hk", "https://www.rthk.hk/feeds/dtt/rthktv32_https.m3u8"),
        Triple("RTHK TV 33", "RTHKTV33.hk", "https://rthktv33-live.akamaized.net/hls/live/2101641/RTHKTV33/stream03/streamPlaylist.m3u8"),
        Triple("RTHK TV 34", "RTHKTV34.hk", "https://rthktv34-live.akamaized.net/hls/live/2101642/RTHKTV34/stream02/streamPlaylist.m3u8"),
    )
    private val hoy = listOf(
        Triple("HOY TV", "HOYTV.hk", "https://hoytv-live-stream.hoy.tv/ch77/index-fhd.m3u8"),
        Triple("HOY Infotainment", "HOYInfotainment.hk", "https://hoytv-live-stream.hoy.tv/ch78/index-fhd.m3u8"),
    )
    fun recognizesPage(url: String) = url.trimEnd('/') in listOf(RTHK_PAGE.trimEnd('/'), HOY_PAGE)
    fun recognizesStream(url: String): Boolean = httpUri(url)?.let { uri ->
        uri.scheme == "https" && uri.fragment == null && uri.query == null && (rthk + hoy).any { it.third == url }
    } == true
    fun playlist(url: String): String {
        require(recognizesPage(url))
        val entries = if (url.trimEnd('/') == RTHK_PAGE.trimEnd('/')) rthk else hoy
        return "#EXTM3U\n" + entries.joinToString("") { (name, id, stream) ->
            "#EXTINF:-1 tvg-id=\"$id\" group-title=\"香港 · 综合\",$name\n$stream\n"
        }
    }
}
