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
