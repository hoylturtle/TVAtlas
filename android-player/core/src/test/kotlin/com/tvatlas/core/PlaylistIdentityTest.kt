package com.tvatlas.core

import com.tvatlas.core.playlist.M3uParser
import com.tvatlas.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistIdentityTest {
    @Test fun strayDurationCommaCannotTurnMetadataIntoChannelName() {
        val text = """#EXTM3U
#EXTINF:-1 tvg-id="河北卫视" group-title="中国大陆 · 卫视",河北卫视
https://fixture.test/first
#EXTINF:-1,tvg-id="河北卫视" tvg-name="河北卫视" tvg-logo="https://fixture.test/logo?x=1,2" group-title="中国大陆 · 卫视",河北卫视
https://fixture.test/second
"""
        val channel = M3uParser.parse(text, "p").single()
        assertEquals("河北卫视", channel.name)
        assertEquals("中国大陆 · 卫视", channel.group)
        assertEquals("https://fixture.test/logo?x=1,2", M3uParser.parse(text.substringAfter("https://fixture.test/first"), "p").single().logo)
        assertEquals(2, channel.streams.size)
    }
    @Test fun titleCommasAndQuotedMetadataCommasRemainIntact() {
        val text = """#EXTINF:0 group-title="News, World",News, English
https://fixture.test/live
"""
        val channel = M3uParser.parse(text, "p").single()
        assertEquals("News, English", channel.name)
        assertEquals("News, World", channel.group)
    }

    @Test fun sameNamedChannelsFromDifferentRegionsRemainSeparate() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"中国大陆\",新闻\nhttps://example.test/cn\n#EXTINF:-1 group-title=\"日本\",新闻\nhttps://example.test/jp\n"
        val channels = M3uParser.parse(text, "p")
        assertEquals(2, channels.size)
        assertNotEquals(channels[0].id, channels[1].id)
        assertEquals(setOf(PlaybackRegion.MAINLAND, PlaybackRegion.JAPAN), channels.map { it.playbackRegion() }.toSet())
    }
    @Test fun rotatingAuthenticationRetainsLineButBusinessParametersDistinguishChannels() {
        fun parse(url: String) = M3uParser.parse("#EXTINF:-1,fixture\n$url", "p").single().streams.single().id
        assertEquals(parse("https://example.test/live?channel=1&token=old&expires=1"), parse("https://example.test/live?expires=2&token=new&channel=1"))
        assertNotEquals(parse("https://example.test/live?channel=1&token=a"), parse("https://example.test/live?channel=2&token=a"))
    }
    @Test fun sharedTokenEndpointDoesNotMergeDistinctChannels() {
        val channels = M3uParser.parse("#EXTINF:-1,One\nhttps://example.test/live?token=one\n#EXTINF:-1,Two\nhttps://example.test/live?token=two\n", "p")
        assertEquals(2, channels.size)
        assertNotEquals(channels[0].streams.single().id, channels[1].streams.single().id)
    }
    @Test fun hongKongHasThreeGroupsAndOtherRegionsUseCompactBrowsingGroups() {
        val channels = listOf("香港 · 音乐", "香港 · 体育", "香港 · 纪实", "香港 · 新闻", "香港 · 影视").map { Channel(it, it, it, streams = emptyList()) }
        assertEquals(setOf("香港 · 综合", "香港 · 新闻", "香港 · 影视"), channels.map { it.browseGroup() }.toSet())
        assertEquals("大陆频道", channels.first().copy(group = "中国大陆 · 体育").browseGroup())
        assertEquals("台湾频道", channels.first().copy(group = "台湾 · 电影").browseGroup())
        assertEquals("澳门频道", channels.first().copy(group = "澳门 · 综合").browseGroup())
    }
}
