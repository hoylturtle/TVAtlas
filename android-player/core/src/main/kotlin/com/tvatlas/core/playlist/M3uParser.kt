package com.tvatlas.core.playlist

import com.tvatlas.core.model.*

object M3uParser {
    private val attribute = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    fun parse(text: String, playlistId: String): List<Channel> {
        val channels = linkedMapOf<String, Channel>()
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
                    metadata = if (separator >= 0) {
                        line.substring(separator + 1).trim() to attribute.findAll(line.substring(0, separator))
                            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    } else null
                }
                line.isBlank() || line.startsWith("#") -> Unit
                else -> {
                    val info = metadata
                    metadata = null
                    if (info != null && info.first.isNotBlank() && httpUri(line) != null) {
                        val (name, attrs) = info
                        val old = channels[name]
                        val stream = Stream(stableId("$playlistId\u0000$line"), line, playlistId)
                        if (old == null) {
                            channels[name] = Channel(stableId(name), name, attrs["group-title"].orEmpty(),
                                attrs["tvg-logo"], attrs["tvg-id"], listOf(stream))
                        } else if (old.streams.none { it.url == line }) {
                            channels[name] = old.copy(streams = old.streams + stream)
                        }
                    }
                }
            }
        }
        return channels.values.toList()
    }
}
