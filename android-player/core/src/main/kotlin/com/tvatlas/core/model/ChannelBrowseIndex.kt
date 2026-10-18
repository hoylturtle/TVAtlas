package com.tvatlas.core.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable data class ChannelBrowseMetadata(
    val fingerprint: String, val region: String, val type: String, val rank: Int, val hongKong: Boolean,
)

data class ChannelBrowseBuild(val index: ChannelBrowseIndex, val entries: Map<String, ChannelBrowseMetadata>, val reused: Int, val calculated: Int)

/** Presentation metadata only; current channel objects and source order always come from the library. */
class ChannelBrowseIndex private constructor(
    val channels: List<Channel>,
    private val metadata: Map<String, ChannelBrowseMetadata>,
) {
    val regions: List<String> = channels.map { region(it) }.distinct()
    fun region(channel: Channel): String = metadata.getValue(channel.id).region
    fun type(channel: Channel): String = metadata.getValue(channel.id).type
    fun primaryFirst(channels: List<Channel>): List<Channel> = channels.sortedBy { metadata.getValue(it.id).rank }

    companion object {
        // Bump when the presentation classifiers or fingerprint inputs change.
        const val RULE_VERSION = 1
        fun build(channels: List<Channel>): ChannelBrowseIndex = update(channels, emptyMap()).index

        fun update(channels: List<Channel>, previous: Map<String, ChannelBrowseMetadata>): ChannelBrowseBuild {
            val digest = MessageDigest.getInstance("SHA-256")
            val metadata = LinkedHashMap<String, ChannelBrowseMetadata>(channels.size)
            val entries = LinkedHashMap<String, ChannelBrowseMetadata>(channels.size)
            var reused = 0
            for (channel in channels) {
                val key = hash(digest, listOf(channel.id))
                // Only the two URL predicates used by browseType affect classification. Never store URLs.
                val fingerprint = hash(digest, listOf(channel.name, channel.group, channel.tvgId.orEmpty(),
                    channel.streams.any { it.url.contains("viu.tv/", true) }.toString(),
                    channel.streams.any { it.url.contains("mytvsuper.com/", true) }.toString()))
                val saved = previous[key]?.takeIf {
                    it.fingerprint == fingerprint && it.region.length in 1..64 && it.type.length in 1..64 && it.rank in 0..1000
                }
                val item = if (saved != null) { reused++; saved } else {
                    val type = channel.browseType()
                    ChannelBrowseMetadata(fingerprint, channel.browseRegion(), type, channel.primaryChannelRank(type), channel.isHongKong())
                }
                metadata[channel.id] = item
                entries[key] = item
            }
            val (hongKong, remaining) = channels.partition { metadata.getValue(it.id).hongKong }
            return ChannelBrowseBuild(ChannelBrowseIndex(hongKong + remaining, metadata), entries, reused, channels.size - reused)
        }

        private fun hash(digest: MessageDigest, values: List<String>): String {
            digest.reset()
            for (value in values) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                for (shift in 24 downTo 0 step 8) digest.update((bytes.size ushr shift).toByte())
                digest.update(bytes)
            }
            val bytes = digest.digest()
            val hex = "0123456789abcdef"
            return CharArray(bytes.size * 2).also { chars -> bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 255
                chars[index * 2] = hex[value ushr 4]; chars[index * 2 + 1] = hex[value and 15]
            } }.concatToString()
        }
    }
}
