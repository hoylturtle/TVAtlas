package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class MediaSubscriptionTest {
    @Test fun liveImportBudgetRejectsHugeListsWithoutSilentTruncation() {
        assertEquals(6000, MediaSubscription.checkedLiveBudget(2000, 2000, 4000))
        for ((channels, streams, existing) in listOf(Triple(2001, 2001, 0), Triple(100, 101, 5900))) {
            try { MediaSubscription.checkedLiveBudget(channels, streams, existing); fail("Expected bounded import") }
            catch (_: MediaSubscriptionLimitException) { }
        }
    }
    @Test fun importsBothKindsDeduplicatesAndPreservesSignedQueryAndEpg() {
        val text = """{"sources":[{"name":"影视","url":"/api?token=a%2Bb"},{"name":"重复","url":"/api?token=a%2Bb"}],
            "liveSources":[{"name":"直播","url":"/list.m3u?sig=a%2Fb","epg":"/guide.xml"}]}"""
        val parsed = MediaSubscription.parse(text, "https://fixture.test/config.json")
        assertEquals("https://fixture.test/api?token=a%2Bb", parsed.vod.single().api)
        assertEquals("https://fixture.test/list.m3u?sig=a%2Fb", parsed.live.single().url)
        assertEquals("https://fixture.test/guide.xml", parsed.live.single().epg)
    }
    @Test fun acceptsLiveOnlyAndRejectsExecutableOrCredentialBearingSources() {
        val parsed = MediaSubscription.parse("""{"liveSources":[{"name":"直播","url":"https://fixture.test/list.m3u"}],
            "sources":[{"url":"https://fixture.test/guard.jar?key=1"},{"url":"http://127.0.0.1/private"},{"url":"https://u:p@fixture.test/api"}]}""", "https://fixture.test/config")
        assertTrue(parsed.vod.isEmpty()); assertEquals(1, parsed.live.size)
    }
    @Test fun protectedSpiderIsStillNotExecutedOrMisrepresentedAsNative() {
        val parsed = MediaSubscription.parse("""{"sites":[{"type":3,"name":"保护入口","api":"csp_Guard"},{"type":1,"name":"原生","api":"https://fixture.test/api"}]}""", "https://fixture.test/config")
        assertEquals(1, parsed.vod.size); assertEquals(1, parsed.native!!.unsupported)
    }
}
