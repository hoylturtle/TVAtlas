package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UnifiedVodRepositoryTest {
    @Test fun subscriptionChangeRestartsInFlightInitialBrowseWithoutLoadMore() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("vod-source-race").toFile()
            val old = server.url("/old").toString()
            val next = server.url("/next").toString()
            val started = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().setBody("""{"page":1,"pagecount":1,"list":[{"vod_id":"new","vod_name":"首屏影片","type_name":"动作片"}]}"""))
            try {
                val repository = UnifiedVodRepository(listOf(old), directory, this) { source ->
                    if (source == old) { started.complete(Unit); awaitCancellation() }
                    VodCatalogClient(OkHttpClient())
                }
                repository.browse(VodFilter(), "")
                withTimeout(5000) { started.await() }
                repository.updateSources(listOf(next))
                val loaded = withTimeout(5000) { repository.state.first { !it.loading && it.completed == 1 } }
                assertEquals("首屏影片", loaded.items.single().movie.name)
                assertEquals(1, server.requestCount)
                assertTrue(server.takeRequest().path!!.startsWith("/next"))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun recentFeedLoadsBeforeTaxonomyAndOfflineRefreshRetainsShelfThenReplacesChangedContent() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("recent-shelf").toFile()
            fun response(id: String, title: String) = MockResponse().setBody("""{"page":1,"pagecount":3,"list":[{"vod_id":"$id","vod_name":"$title","type_name":"动作片","vod_time":"2026-01-02 03:04:05","vod_play_url":"正片${'$'}https://fixture.test/live.m3u8?token=secret"}]}""")
            try {
                server.enqueue(response("1", "旧片"))
                val sources = listOf(server.url("/vod").toString())
                val first = UnifiedVodRepository(sources, directory, this) { VodCatalogClient(OkHttpClient()) }
                first.browse(VodFilter(), "")
                withTimeout(5000) { first.state.first { !it.loading && it.completed == 1 } }
                assertEquals(1, server.requestCount)
                val request = server.takeRequest()
                assertEquals("detail", request.requestUrl!!.queryParameter("ac"))
                assertNull(request.requestUrl!!.queryParameter("t"))
                directory.listFiles()!!.forEach { assertFalse(it.readText().contains("token=secret")) }
                val second = UnifiedVodRepository(sources, directory, this) { VodCatalogClient(OkHttpClient()) }
                server.enqueue(MockResponse().setResponseCode(503).setHeadersDelay(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                second.browse(VodFilter(), "", force = true)
                val cached = withTimeout(5000) { second.state.first { it.loading && it.items.isNotEmpty() } }
                assertEquals("旧片", cached.items.single().movie.name)
                val offline = withTimeout(5000) { second.state.first { !it.loading && it.completed == 1 } }
                assertEquals("旧片", offline.items.single().movie.name)
                assertNull(offline.error)
                assertTrue(offline.notice!!.contains("上次浏览"))
                server.enqueue(response("2", "新片"))
                second.refreshDue(0)
                val updated = withTimeout(5000) { second.state.first { !it.loading && it.items.singleOrNull()?.movie?.name == "新片" } }
                assertEquals(1, updated.items.size)
                assertNull(updated.error)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun mergesSourcesCachesOnlyMetadataAndFetchesFreshPlayableDetails() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val source = request.requestUrl!!.encodedPath.trim('/')
                    val listOnly = request.requestUrl!!.queryParameter("ac") == "list"
                    val body = if (listOnly) """{"class":[{"type_id":"6","type_name":"动作片"}],"list":[]}""" else
                        """{"page":1,"pagecount":2,"list":[{"vod_id":"$source","vod_name":"同一影片","type_name":"动作片","vod_year":"2026","vod_area":"中国大陆","vod_play_from":"高清","vod_play_url":"正片${'$'}https://$source.test/movie.m3u8?token=private"}]}"""
                    return MockResponse().setBody(body)
                }
            }
            val directory = Files.createTempDirectory("unified-vod").toFile()
            val sources = listOf(server.url("/a").toString(), server.url("/b").toString())
            try {
                val repository = UnifiedVodRepository(sources, directory, this) { VodCatalogClient(OkHttpClient()) }
                val filter = VodFilter(VodKind.MOVIE, "动作", "大陆", "2026")
                repository.browse(filter, "")
                val first = withTimeout(5000) { repository.state.first { !it.loading && it.completed == 2 } }
                assertEquals(1, first.items.size)
                assertEquals(2, first.items.single().offers.size)
                assertTrue(first.items.single().movie.episodes.isEmpty())
                assertEquals(4, server.requestCount)
                directory.listFiles()!!.forEach { assertFalse(it.readText().contains("token=private")) }
                repository.browse(filter, "")
                withTimeout(5000) { repository.state.first { !it.loading && it.completed == 2 } }
                assertEquals(4, server.requestCount)
                val fresh = repository.details(first.items.single())
                assertEquals(2, fresh.movie.episodes.size)
                assertTrue(fresh.movie.episodes.all { it.url.contains("token=private") })
                repository.browse(filter, "", force = true)
                withTimeout(5000) { repository.state.first { !it.loading && it.completed == 2 } }
                assertEquals(10, server.requestCount)
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun failedSourceDoesNotHideSuccessfulSourceAndTaxonomyCanBeMissing() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/bad")) return MockResponse().setResponseCode(503)
                    if (request.requestUrl!!.queryParameter("ac") == "list") return MockResponse().setResponseCode(404)
                    return MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"电影","type_name":"动作片","vod_year":"2026"}]}""")
                }
            }
            val directory = Files.createTempDirectory("unified-partial").toFile()
            try {
                val repository = UnifiedVodRepository(listOf(server.url("/good").toString(), server.url("/bad").toString()), directory, this) { VodCatalogClient(OkHttpClient()) }
                repository.browse(VodFilter(), "")
                val state = withTimeout(5000) { repository.state.first { !it.loading && it.completed == 2 } }
                assertEquals(1, state.items.size)
                assertEquals(1, state.failed)
                assertNull(state.error)
                assertTrue(state.notice!!.contains("当前可用片单"))
            } finally { directory.deleteRecursively() }
        }
    }
}
