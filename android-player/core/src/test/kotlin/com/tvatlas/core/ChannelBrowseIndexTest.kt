package com.tvatlas.core

import com.tvatlas.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ChannelBrowseIndexTest {
    private fun channel(id: String, name: String, group: String, tvg: String? = null) = Channel(id, name, group, tvgId = tvg, streams = emptyList())
    @Test fun preservesStableRegionOrderAndFlagshipChannelOrder() {
        val channels = listOf(channel("cn", "CCTV-2", "中国大陆"), channel("p", "明珠台", "香港"), channel("j", "翡翠台", "香港"), channel("jp", "NHK", "日本"))
        val index = ChannelBrowseIndex.build(channels)
        assertEquals(listOf("p", "j", "cn", "jp"), index.channels.map { it.id })
        assertEquals(listOf("香港", "大陆", "日本"), index.regions)
        assertEquals(listOf("j", "p"), index.primaryFirst(channels.filter { index.region(it) == "香港" }).map { it.id })
        assertEquals("CCTV", index.type(channels.first()))
    }
    @Test fun rebuildReflectsUpdatedGroupEvenWhenIdStaysTheSame() {
        val before = channel("id", "地方台", "日本")
        val old = ChannelBrowseIndex.build(listOf(before))
        val after = before.copy(group = "韩国")
        val updated = ChannelBrowseIndex.build(listOf(after))
        assertEquals("日本", old.region(before))
        assertEquals("韩国", updated.region(after))
    }
    @Test fun fixedCountryPatternsRetainBoundariesAliasesAndTvgSuffixes() {
        val cases = mapOf("TW" to PlaybackRegion.TAIWAN, "JPN" to PlaybackRegion.JAPAN, "🇰🇷" to PlaybackRegion.KOREA,
            "united-states" to PlaybackRegion.UNITED_STATES, "gb" to PlaybackRegion.UNITED_KINGDOM,
            "🇲🇴" to PlaybackRegion.HONG_KONG, "Singapore" to PlaybackRegion.SINGAPORE, "CHN" to PlaybackRegion.MAINLAND)
        cases.forEach { (name, region) -> assertEquals(name, region, playbackRegion(name)) }
        assertNull(playbackRegion("showtwenty"))
        assertEquals(PlaybackRegion.TAIWAN, channel("tw", "台", "未知", "channel.tw@HD").playbackRegion())
        assertEquals(PlaybackRegion.JAPAN, channel("jp", "台", "日本", "channel.hk").playbackRegion())
    }
    @Test fun largeSnapshotKeepsEveryChannelAndEqualRankSourceOrder() {
        val channels = (1..15000).map { channel("$it", "地方台$it", if (it % 2 == 0) "香港" else "中国大陆") }
        val index = ChannelBrowseIndex.build(channels)
        assertEquals(15000, index.channels.size)
        assertEquals(channels.filter { it.group == "香港" }.map { it.id }, index.channels.take(7500).map { it.id })
        assertEquals(channels.map { it.id }, index.primaryFirst(channels).map { it.id })
    }
}
