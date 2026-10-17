package com.tvatlas.player.ui

import org.junit.Assert.*
import org.junit.Test

class BundledChannelArtworkTest {
    @Test fun resolvesCctvAliasesAndDistinctChannelBranding() {
        assertEquals(extraBundledArtwork("CCTV-1 综合"), extraBundledArtwork("CCTV1"))
        assertEquals(extraBundledArtwork("CCTV-5+ 体育赛事"), extraBundledArtwork("CCTV5+"))
        assertNotNull(extraBundledArtwork("湖南卫视"))
        assertNotNull(extraBundledArtwork("SUPER FREE"))
        assertNotNull(extraBundledArtwork("MOMO 综合台"))
        assertNotEquals(extraBundledArtwork("TVB Plus"), extraBundledArtwork("无线新闻台"))
        assertNotEquals(extraBundledArtwork("SUPER FREE"), extraBundledArtwork("SUPER Sports"))
        assertNull(extraBundledArtwork("不存在的频道"))
    }
}
