package com.tvatlas.player.vod

import com.tvatlas.core.vod.CatalogEpisode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class VodLineSelectorTest {
    @Test fun repeatedSelectionUsesRouteScopedCacheAndActualPlayerResolution() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nvideo.m3u8")
            }
            val dir = java.nio.file.Files.createTempDirectory("line-quality").toFile()
            try {
                val cache = VodQualityCache(dir.resolve("quality.json"))
                val a = CatalogEpisode("正片", server.url("/a").toString(), "a", 2160)
                val b = CatalogEpisode("正片", server.url("/b").toString(), "b", 720)
                val selector = VodLineSelector(OkHttpClient(), cache, { "direct" })
                selector.order(listOf(a, b)); assertEquals(2, server.requestCount)
                cache.record(a.url, "direct", 480, 10, actual = true)
                assertEquals(b, selector.order(listOf(a, b)).first())
                assertEquals(2, server.requestCount)
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun ranksReachableMediaBeforeHtmlAndHttpErrorsAndPreservesSignedUrls() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/slow") -> MockResponse().setBody("#EXTM3U\n#EXT-X-ENDLIST").setHeadersDelay(150, TimeUnit.MILLISECONDS)
                    request.path!!.startsWith("/html") -> MockResponse().setBody("<html>denied</html>")
                    request.path!!.startsWith("/denied") -> MockResponse().setResponseCode(403)
                    else -> MockResponse().setBody("#EXTM3U\n#EXT-X-ENDLIST")
                }
            }
            val candidates = listOf("slow", "html", "denied", "fast").map { name ->
                CatalogEpisode("第1集", server.url("/$name.m3u8?token=a%24b").toString(), name)
            }
            val ordered = VodLineSelector(OkHttpClient()).order(candidates)
            assertEquals("fast", ordered.first().line)
            assertEquals("slow", ordered[1].line)
            assertEquals(candidates.toSet(), ordered.toSet())
            assertTrue(ordered.all { it.url.contains("token=a%24b") })
        }
    }
    @Test fun verifiedHigherResolutionWinsOverFasterLowResolution() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/high"))
                    MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160\nvideo.m3u8").setHeadersDelay(100, TimeUnit.MILLISECONDS)
                    else MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\nvideo.m3u8")
            }
            val low = CatalogEpisode("正片", server.url("/low.m3u8").toString(), "low", 720)
            val high = CatalogEpisode("正片", server.url("/high.m3u8").toString(), "high", 0)
            assertEquals(high, VodLineSelector(OkHttpClient()).order(listOf(low, high)).first())
        }
    }
    @Test fun singleLineDoesNotDelayPlaybackWithAProbe() = runBlocking {
        MockWebServer().use { server ->
            val only = CatalogEpisode("正片", server.url("/main.mp4").toString(), "one")
            assertEquals(listOf(only), VodLineSelector(OkHttpClient()).order(listOf(only)))
            assertEquals(0, server.requestCount)
        }
    }
}
