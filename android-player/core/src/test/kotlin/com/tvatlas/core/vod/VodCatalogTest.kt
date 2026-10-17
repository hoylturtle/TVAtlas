package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class VodCatalogTest {
    @Test fun separatesLinesAndRetainsSignedMediaQueries() {
        val text = """{"page":2,"pagecount":9,"list":[{"vod_id":1,"vod_name":"电影","vod_play_from":"高清${'$'}${'$'}${'$'}备用","vod_play_url":"正片${'$'}https://a.example/v.m3u8?token=a%24b#网页${'$'}https://a.example/watch/1${'$'}${'$'}${'$'}正片${'$'}https://b.example/v.mp4"}]}"""
        val result = VodCatalog.parse(text)
        assertEquals(2, result.page)
        assertEquals(9, result.pages)
        assertEquals(listOf("高清", "备用"), result.movies.single().episodes.map { it.line })
        assertEquals("https://a.example/v.m3u8?token=a%24b", result.movies.single().episodes.first().url)
    }
    @Test fun rejectsExecutableAndCredentialUrlsAndDeduplicatesIds() {
        val result = VodCatalog.parse("""{"list":[{"vod_id":"1","vod_name":"a","vod_play_url":"a${'$'}javascript:alert(1)#b${'$'}https://user:pass@a.example/v.mp4"},{"vod_id":"1","vod_name":"a"}]}""")
        assertEquals(1, result.movies.size)
        assertTrue(result.movies.single().episodes.isEmpty())
    }
    @Test fun retainsArtworkAndPlainSummaryButRejectsUnsafePosterUrls() {
        val result = VodCatalog.parse("""{"list":[{"vod_id":"1","vod_name":"Movie","vod_pic":"https://images.example/poster.jpg","vod_content":"<p>A story</p><br> about friendship"},{"vod_id":"2","vod_name":"Other","vod_pic":"javascript:alert(1)"}]}""")
        assertEquals("https://images.example/poster.jpg", result.movies[0].poster)
        assertEquals("A story about friendship", result.movies[0].summary)
        assertNull(result.movies[1].poster)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonCatalogJson() {
        VodCatalog.parse("""{"error":"unauthorized"}""")
    }
}
