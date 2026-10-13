package com.tvatlas.player.epg
import org.junit.Assert.*
import org.junit.Test

class OfficialGuideTest {
    @Test fun tvbScheduleUsesHongKongTimeAndInfersEndFromNextProgramme() {
        val programmes = OfficialGuide.parse("""[{"item":[{"epg":[{"start_datetime":"2026-10-13 12:00:00","programme_title_tc":"新闻"},{"start_datetime":"2026-10-13 12:30:00","programme_title_tc":"节目"}]}]}]""", "Jade.hk", true)
        assertEquals(2, programmes.size)
        assertEquals(com.tvatlas.core.epg.XmlTv.time("20261013040000 +0000"), programmes[0].start)
        assertEquals(programmes[1].start, programmes[0].end)
    }
    @Test fun viuScheduleAcceptsIsoOffsetsAndRetainsExplicitEnd() {
        val programmes = OfficialGuide.parse("""[[{"start":"2026-10-13T12:00:00+08:00","end":"2026-10-13T12:30:00+08:00","name":"Viu programme"}]]""", "ViuTV.hk", false)
        assertEquals(1, programmes.size)
        assertEquals(1800000L, programmes[0].end - programmes[0].start)
    }
}
