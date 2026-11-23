package com.tvatlas.player.vod

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class VodInsertionEvidenceTest {
    @Test fun ordinaryCutsAndAdWordsAreNotInsertionEvidence() {
        assertFalse(hasVodInsertionMarkers("#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:6,\nad.ts\n"))
        assertFalse(hasVodInsertionMarkers("#EXTM3U\n#EXT-X-DATERANGE:ID=\"chapter\",START-DATE=\"2026-01-01\""))
        assertTrue(hasVodInsertionMarkers("#EXTM3U\n#EXT-X-CUE-OUT:30"))
        assertTrue(hasVodInsertionMarkers("#EXTM3U\n#EXT-X-DATERANGE:ID=\"1\",SCTE35-OUT=0x123"))
    }
    @Test fun confirmedInspectionDoesNotRewriteBytesOrMakeExtraRequests() {
        val body = "#EXTM3U\n#EXTINF:1,\npre.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\nad.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\npost.ts\n#EXT-X-ENDLIST\n"
        MockWebServer().use { server ->
            val url = server.url("/media.m3u8")
            val fingerprint = com.tvatlas.core.vod.ConfirmedVodAds.snapshot(url.toString(), body)!!.fingerprint
            val rule = com.tvatlas.core.vod.ConfirmedAdRule("test-verified", url.host, url.encodedPath, url.encodedPath, null, fingerprint, 3, listOf(1..1))
            var matched: com.tvatlas.core.vod.ConfirmedAdMatch? = null
            val session = com.tvatlas.core.vod.ConfirmedAdPlaylistSession(url.toString(), listOf(rule))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(body))
            val client = vodMediaClient(OkHttpClient(), { true }, null, onConfirmedAds = { matched = it }, adSession = session)
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(body, it.body!!.string()) }
            assertEquals(1, server.requestCount)
            assertEquals(listOf(com.tvatlas.core.vod.VodSkipRange(1000,2000)), matched!!.ranges)
        }
    }
    @Test fun inspectionLeavesPlaylistUnchangedAndUsesOnlyExistingRequest() {
        val body = "#EXTM3U\n#EXT-X-CUE-OUT:30\n#EXTINF:6,\nsegment.ts\n"
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(body))
            server.start()
            var evidence: Boolean? = null
            val client = vodMediaClient(OkHttpClient(), { true }, null) { evidence = it }
            client.newCall(Request.Builder().url(server.url("/manifest")).build()).execute().use {
                assertEquals(body, it.body!!.string())
            }
            assertEquals(true, evidence)
            assertEquals(1, server.requestCount)
        }
    }
}
