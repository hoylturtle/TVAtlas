package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import com.tvatlas.core.vod.stremio.*
import com.tvatlas.player.vod.stremio.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class StremioAddonClientTest {
    private val manifest = """{"id":"example","name":"Example","types":["movie","series"],"resources":["catalog","meta","stream"],"catalogs":[{"id":"top","type":"movie","extra":[{"name":"search"},{"name":"skip"}]}]}"""
    @Test fun configuredPathSurvivesAndSearchCannotInjectOtherResources() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest))
            server.enqueue(MockResponse().setBody("""{"metas":[{"id":"tt1234567","type":"movie","name":"Example"}]}"""))
            server.enqueue(MockResponse().setBody("""{"meta":{"id":"tt1234567","type":"movie","name":"Example"}}"""))
            server.enqueue(MockResponse().setBody("""{"streams":[{"url":"https://media.example/movie.mp4"},{"infoHash":"abc"}]}"""))
            val client = StremioAddonClient(OkHttpClient())
            val url = server.url("/private-config/manifest.json").toString()
            val entry = AddonEntry(url, client.manifest(url))
            assertEquals("/private-config/manifest.json", server.takeRequest().path)
            val query = "中文 /../../stream&skip=999"
            val page = client.catalog(entry, 1, query, null, "movie")
            assertTrue(page.movies.single().discoveryOnly)
            val request = server.takeRequest().requestUrl!!
            assertEquals(listOf("private-config", "catalog", "movie", "top"), request.pathSegments.take(4))
            val extra = request.encodedPath.substringAfter("/top/").removeSuffix(".json")
            assertEquals("search=$query", java.net.URLDecoder.decode(extra, "UTF-8"))
            val detail = client.catalog(entry, 1, "", "movie:tt1234567", null).movies.single()
            assertEquals("movie:tt1234567", detail.episodes.single().streamId)
            assertEquals("/private-config/meta/movie/tt1234567.json", server.takeRequest().path)
            val streams = client.streams(entry, "movie", "tt1234567", "正片")
            assertEquals(1, streams.episodes.size); assertEquals(1, streams.unsupported)
        }
    }
    @Test fun paginationUsesActualOffsetAndNeverMakesUpPageSize() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"metas":[]}"""))
            val entry = AddonEntry(server.url("/manifest.json").toString(), StremioProtocol.manifest(manifest))
            val page = StremioAddonClient(OkHttpClient()).catalog(entry, 2, "", null, "movie", offset = 20)
            assertEquals("/catalog/movie/top/skip=20.json", server.takeRequest().path)
            assertEquals(2, page.pages)
        }
    }
    @Test fun manifestRedirectDoesNotLeakConfigurationPathToAnotherServer() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/steal")))
            try { StremioAddonClient(OkHttpClient()).manifest(origin.url("/secret/manifest.json").toString()); fail() }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("302")); assertFalse(e.message!!.contains("secret")) }
            assertEquals(0, other.requestCount)
        } }
    }
    @Test fun mediaHeadersStayAtOriginalOriginAcrossRedirects() {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/video.mp4")))
            other.enqueue(MockResponse().setBody("video"))
            val episode = CatalogEpisode("正片", origin.url("/start").toString(), "line", headers = mapOf("Authorization" to "Bearer private", "Cookie" to "token=private", "Referer" to "https://private.example"))
            addonMediaClient(OkHttpClient(), episode).newCall(Request.Builder().url(episode.url).build()).execute().close()
            assertEquals("Bearer private", origin.takeRequest().getHeader("Authorization"))
            val redirected = other.takeRequest()
            assertNull(redirected.getHeader("Authorization")); assertNull(redirected.getHeader("Cookie")); assertNull(redirected.getHeader("Referer"))
        } }
    }
    @Test fun bundledCatalogueNeverRequestsManifestFromNetwork() = runBlocking {
        val entry = AddonEntry(OpenFilmsAddon.ADDRESS, OpenFilmsAddon.manifest)
        val api = StremioAddonClient(OkHttpClient())
        assertEquals(2, api.catalog(entry, 1, "", null, "movie").movies.size)
        assertEquals(1, api.catalog(entry, 1, "辛特尔", null, "movie").movies.size)
        val detail = api.catalog(entry, 1, "", "movie:sintel", null).movies.single()
        assertEquals("movie:sintel", detail.episodes.single().streamId)
        assertEquals(1, api.streams(entry, "movie", "sintel", "正片").episodes.size)
    }
}
