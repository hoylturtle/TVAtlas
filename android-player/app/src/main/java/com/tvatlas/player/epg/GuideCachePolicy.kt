package com.tvatlas.player.epg

import com.tvatlas.core.epg.Guide
import com.tvatlas.core.epg.Programme
import com.tvatlas.core.model.Channel

/** Keep current/upcoming programmes for the actual library, not every station in XMLTV. */
object GuideCachePolicy {
    const val MAX_PROGRAMMES = 6000
    const val MAX_PER_CHANNEL = 96
    fun retain(guide: Guide, channels: List<Channel>, now: Long): Guide {
        val grouped = guide.programmes.groupBy { it.channel }
        val aliases = guide.names.flatMap { (id, names) -> names.map { Guide.normalize(it) to id } }.toMap()
        val prefixes = grouped.keys.distinctBy { it.substringBefore('@') }.associateBy { it.substringBefore('@') }
        val wanted = channels.mapNotNull { channel ->
            channel.tvgId?.let { if (it in grouped) it else prefixes[it] }
                ?: aliases[Guide.normalize(channel.name)] ?: channel.id.takeIf { it in grouped }
        }.toSet()
        val end = now + 24 * 60 * 60 * 1000L
        val lists = wanted.map { id -> grouped[id].orEmpty().asSequence()
            .filter { it.end > now && it.start < end }.sortedBy { it.start }.distinct()
            .take(MAX_PER_CHANNEL).toList() }
        // Round-robin keeps each channel's current/next entries before filling later slots.
        val kept = ArrayList<Programme>()
        for (position in 0 until MAX_PER_CHANNEL) {
            for (list in lists) {
                list.getOrNull(position)?.let { if (kept.size < MAX_PROGRAMMES) kept.add(it) }
            }
            if (kept.size >= MAX_PROGRAMMES) break
        }
        val ids = kept.map { it.channel }.toSet()
        return Guide(guide.names.filterKeys { it in ids }, kept)
    }
}
