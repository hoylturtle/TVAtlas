package com.tvatlas.core.playlist

import com.tvatlas.core.model.*

object M3uParser {
    private val attribute = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")
    /** Only known credentials/expiry parameters rotate; retain channel/quality/business parameters. */
    fun streamIdentity(url: String): String {
        val uri = httpUri(url) ?: return url
        val transient = setOf("sig", "signature", "token", "auth_token", "access_token", "expires", "expiry", "expire", "ts", "ext_start_limit", "int_start_limit")
        val query = uri.rawQuery?.split('&')?.filterNot { item ->
            runCatching { java.net.URLDecoder.decode(item.substringBefore('='), "UTF-8").lowercase() }.getOrNull() in transient
        }?.sorted()?.joinToString("&").orEmpty()
        return "${uri.scheme.lowercase()}://${uri.rawAuthority}${uri.rawPath}" + if (query.isBlank()) "" else "?$query"
    }

    private fun header(key: String, value: String): Pair<String, String>? {
        val name = when (key.lowercase()) {
            "http-user-agent", "user-agent" -> "User-Agent"
            "http-referrer", "http-referer", "referer" -> "Referer"
            "origin", "http-origin" -> "Origin"
            "accept" -> "Accept"
            else -> return null
        }
        return (name to value).takeIf { value.isNotBlank() && value.length <= 2048 && value.none { it == '\r' || it == '\n' } }
    }
    fun parse(text: String, playlistId: String): List<Channel> {
        val channels = linkedMapOf<String, Channel>()
        var headers = emptyMap<String, String>()
        var metadata: Pair<String, Map<String, String>>? = null
        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    // The title separator may follow quoted attributes containing commas.
                    var quoted = false
                    val separator = line.indexOfFirst { ch ->
                        if (ch == '"') quoted = !quoted
                        ch == ',' && !quoted
                    }
                    headers = emptyMap()
                    metadata = if (separator >= 0) {
                        line.substring(separator + 1).trim() to attribute.findAll(line.substring(0, separator))
                            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    } else null
                }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val key = line.substringAfter(':').substringBefore('=')
                    val value = line.substringAfter('=', "")
                    header(key, value)?.let { headers = headers + it }
                }
                line.isBlank() || line.startsWith("#") -> Unit
                else -> {
                    val info = metadata
                    metadata = null
                    val url = line.substringBefore('|')
                    val declared = headers
                    headers = emptyMap()
                    val options = line.substringAfter('|', "").split('&').mapNotNull { part ->
                        runCatching { header(java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8"), java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")) }.getOrNull()
                    }.toMap()
                    if (info != null && info.first.isNotBlank() && httpUri(url) != null) {
                        val (name, attrs) = info
                        val tvg = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                        val region = playbackRegion(attrs["group-title"].orEmpty())
                        val matching = channels.values.filter { (it.name == name || (tvg != null && it.tvgId == tvg)) &&
                            (tvg == null || it.tvgId == null || tvg == it.tvgId) &&
                            (region == null || it.playbackRegion() == null || region == it.playbackRegion()) }
                        val old = matching.singleOrNull()
                        val identity = tvg?.let { "tvg\u0000$it" } ?: region?.let { "${it.name}\u0000$name" } ?: name
                        val owner = old?.id ?: stableId(identity)
                        val stream = Stream(stableId("$playlistId\u0000$owner\u0000${streamIdentity(url)}"), url, playlistId, headers = declared + attrs.mapNotNull { (k, v) -> header(k, v) }.toMap() + options)
                        if (old == null) {
                            channels[identity] = Channel(stableId(identity), name, attrs["group-title"].orEmpty(),
                                attrs["tvg-logo"], attrs["tvg-id"], listOf(stream))
                        } else if (old.streams.none { it.url == url }) {
                            val key = channels.entries.first { it.value.id == old.id }.key
                            channels[key] = old.copy(streams = old.streams.filterNot { it.id == stream.id } + stream)
                        }
                    }
                }
            }
        }
        return channels.values.map { it.normalizeHongKongGroup() }
    }
}
