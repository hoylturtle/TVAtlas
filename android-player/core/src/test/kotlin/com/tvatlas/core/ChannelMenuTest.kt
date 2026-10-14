package com.tvatlas.core
import com.tvatlas.core.model.*
import org.junit.Assert.*
import org.junit.Test
class ChannelMenuTest {
    @Test fun examplesUseRegionThenBroadcaster() {
        fun check(name: String, group: String, region: String, type: String) {
            val channel = Channel("id", name, group, streams = emptyList())
            assertEquals(region, channel.browseRegion()); assertEquals(type, channel.browseType())
            assertEquals(group, channel.group)
        }
        check("翡翠台", "香港 · 综合", "香港", "TVB")
        check("ViuTV", "香港 · 综合", "香港", "ViuTV")
        check("港台电视31", "香港 · 综合", "香港", "港台")
        check("CCTV1", "中国大陆 · 央视", "大陆", "CCTV")
        check("广东卫视", "中国大陆 · 卫视", "大陆", "卫视")
        check("澳视澳门", "澳门 · 综合", "澳门", "综合")
        check("CCTV4K", "", "大陆", "CCTV")
    }
    @Test fun officialFreeChannelsStayInTvbAndTaiwanIsNotHongKong() {
        val free = Channel("f", "Rialto Classic Movies", "香港 · 影视", streams = listOf(Stream("s", "https://www.mytvsuper.com/official", "p")))
        assertEquals("TVB", free.browseType())
        assertEquals("台湾", Channel("t", "TVBS新闻", "台湾 · 新闻", streams = emptyList()).browseRegion())
        assertEquals("TVBS", Channel("t", "TVBS新闻", "台湾 · 新闻", streams = emptyList()).browseType())
    }
    private fun sortedNames(group: String, vararg names: String) = names.mapIndexed { index, name ->
        Channel(index.toString(), name, group, streams = emptyList())
    }.primaryChannelsFirst().map { it.name }

    @Test fun tvbFlagshipsLeadAndOtherChannelsKeepSourceOrder() {
        assertEquals(listOf("翡翠台", "明珠台", "TVB Plus", "电影甲", "电影乙"),
            sortedNames("香港 · 综合", "电影甲", "TVB Plus", "电影乙", "明珠台", "翡翠台"))
    }
    @Test fun cctvUsesChannelNumbersAndSupportsHdNames() {
        assertEquals(listOf("CCTV-1 HD", "CCTV2", "CCTV10", "CCTV17", "CCTV4K"),
            sortedNames("中国大陆 · 央视", "CCTV10", "CCTV4K", "CCTV17", "CCTV2", "CCTV-1 HD"))
    }
    @Test fun broadcasterMainChannelsLeadWithoutDroppingDuplicates() {
        assertEquals(listOf("ViuTV", "ViuTV", "ViuTVsix"),
            sortedNames("香港 · 综合", "ViuTVsix", "ViuTV", "ViuTV"))
        assertEquals(listOf("NHK総合", "NHKEテレ", "NHKBS", "NHK World"),
            sortedNames("日本", "NHK World", "NHKBS", "NHKEテレ", "NHK総合"))
    }
}
