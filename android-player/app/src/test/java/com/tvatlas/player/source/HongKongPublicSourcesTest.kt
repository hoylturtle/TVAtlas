package com.tvatlas.player.source

import com.tvatlas.core.playlist.M3uParser
import org.junit.Assert.*
import org.junit.Test

class HongKongPublicSourcesTest {
    @Test fun onlyExactBroadcasterEndpointsAreAccepted() {
        val channels = listOf(HongKongPublicSources.RTHK_PAGE, HongKongPublicSources.HOY_PAGE).flatMap { M3uParser.parse(HongKongPublicSources.playlist(it), it) }
        assertEquals(6, channels.size)
        assertTrue(channels.flatMap { it.streams }.all { HongKongPublicSources.recognizesStream(it.url) })
        assertFalse(HongKongPublicSources.recognizesStream("https://evil.akamaized.net/live.m3u8"))
        assertFalse(HongKongPublicSources.recognizesStream("http://hoytv-live-stream.hoy.tv/ch77/index-fhd.m3u8"))
    }
}
