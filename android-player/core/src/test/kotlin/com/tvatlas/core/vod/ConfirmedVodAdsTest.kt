package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class ConfirmedVodAdsTest {
    private val root = "https://hd.lz-cdn18.com/20230414/1866_7d8603d9/index.m3u8"
    private val child = "https://hd.lz-cdn18.com/20230414/1866_7d8603d9/2000k/hls/mixed.m3u8"
    private fun master() = javaClass.getResourceAsStream("/confirmed-ads/lz-earth2-master.m3u8")!!.bufferedReader().use { it.readText() }
    private fun media() = GZIPInputStream(javaClass.getResourceAsStream("/confirmed-ads/lz-earth2-media.m3u8.gz")!!).bufferedReader().use { it.readText() }
    private fun session() = ConfirmedAdPlaylistSession(root).also { it.inspect(root, master()) }
    @Test fun realVerifiedSnapshotMatchesOnlyAfterExactSingleVariantMaster() {
        val session = ConfirmedAdPlaylistSession(root)
        assertFalse(session.accepts(child))
        assertNull(session.inspect(child, media()).match)
        assertNull(session.inspect(root, master()).match)
        val match = session.inspect(child, media()).match!!
        assertEquals("lz-earth2-2023-v1", match.ruleId)
        assertEquals(listOf(VodSkipRange(303458,329219),VodSkipRange(2008926,2034687),VodSkipRange(3832311,3858072)), match.ranges)
        assertEquals(2618, ConfirmedVodAds.snapshot(child, media())!!.segments.size)
    }
    @Test fun changedFilmSegmentsOrTimingsFailClosedInsteadOfGuessing() {
        val text = media()
        assertNull(session().inspect(child, text.replaceFirst("a82d95c84dc000000.ts", "another-film.ts")).match)
        assertNull(session().inspect(child, text.replaceFirst("4.166667", "5.166667")).match)
        assertFalse(ConfirmedAdPlaylistSession(root).apply { inspect(root, master().replace("mixed.m3u8", "different.m3u8")) }.accepts(child))
        val s = session()
        assertNotNull(s.inspect(child, text).match)
        val changed = s.inspect(child, text.replaceFirst("4.166667", "5.166667"))
        assertTrue(changed.relevant); assertNull(changed.match)
    }
    @Test fun queryRenewalAndLineEndingsDoNotChangeVideoIdentity() {
        val text = media().lineSequence().joinToString("\r\n") { if (it.isNotBlank() && !it.startsWith('#')) "$it?token=new-signature" else it }
        assertEquals(3, session().inspect("$child?token=renewed", text).match!!.ranges.size)
        assertEquals(3, ConfirmedAdPlaylistSession(child).inspect(child, media()).match!!.ranges.size)
    }
    @Test fun unknownHostsCutsAdWordsAndLiveOrEncryptedPlaylistsAreNeverAutoClassified() {
        assertFalse(ConfirmedAdPlaylistSession("https://other.example/movie.m3u8").accepts(child))
        assertNull(session().inspect(child,"#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:26,\nad.ts\n#EXT-X-ENDLIST\n").match)
        assertNull(ConfirmedVodAds.snapshot(child, media().replace("#EXT-X-ENDLIST", "")))
        assertNull(ConfirmedVodAds.snapshot(child, media().replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:10")))
        assertNull(ConfirmedVodAds.snapshot(child, media().replace("#EXTM3U", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"")))
        assertNull(ConfirmedVodAds.snapshot(child, media().replace("#EXTM3U", "#EXTM3U\n#EXT-X-START:TIME-OFFSET=30")))
        assertNull(ConfirmedVodAds.snapshot(child, "#EXTM3U\n" + "x".repeat(ConfirmedVodAds.MAX_BYTES)))
    }
}
