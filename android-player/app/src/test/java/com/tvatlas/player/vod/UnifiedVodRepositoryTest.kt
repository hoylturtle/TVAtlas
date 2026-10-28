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
    @Test fun addonCatalogueDetailsAndDiskCacheUseOpaqueSourceAndDeferredStreams() = runBlocking {
        val directory = Files.createTempDirectory("addon-shelf").toFile()
        val entry = com.tvatlas.player.vod.stremio.AddonEntry(com.tvatlas.core.vod.stremio.OpenFilmsAddon.ADDRESS, com.tvatlas.core.vod.stremio.OpenFilmsAddon.manifest)
        try {
            val repository = UnifiedVodRepository(listOf(entry.source), directory, this) {
                VodCatalogClient(OkHttpClient(), addon = { source -> entry.takeIf { source == entry.source } })
            }
            repository.browse(VodFilter(), "")
            val state = withTimeout(3000) { repository.state.first { !it.loading && it.items.isNotEmpty() } }
            assertEquals(2, state.items.size)
            val detail = repository.details(state.items.first())
            assertTrue(detail.movie.discoveryOnly)
            assertEquals(1, detail.movie.episodes.size)
            assertTrue(detail.movie.episodes.single().streamId.startsWith("movie:"))
            assertTrue(detail.movie.episodes.single().url.startsWith("stremio://"))
            val saved = directory.listFiles()!!.filter { it.extension == "json" }.joinToString { it.readText() }
            assertFalse(saved.contains(entry.address)); assertFalse(saved.contains("archive.org/download/"))
            val offline = UnifiedVodRepository(listOf(entry.source), directory, this) { error("network must not be used") }
            offline.browse(VodFilter(), "", cacheOnly = true)
            val cached = withTimeout(3000) { offline.state.first { !it.loading && it.items.isNotEmpty() } }
            assertEquals(2, cached.items.size); assertTrue(cached.items.all { it.movie.episodes.isEmpty() })
            repository.updateSources(emptyList()); assertTrue(repository.state.value.items.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun homeCacheCanRestoreBothKindsWithoutNetworkOrChangingLibraryFilter() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"page":1,"pagecount":1,"list":[
                    {"vod_id":"m","vod_name":"电影甲","type_name":"动作片","vod_time":100,"vod_play_from":"line","vod_play_url":"正片${'$'}https://example.invalid/m.mp4"},
                    {"vod_id":"s","vod_name":"剧集甲","type_name":"国产剧","vod_time":200,"vod_play_from":"line","vod_play_url":"第1集${'$'}https://example.invalid/s.mp4"}]}""")
            }
            server.start()
            val directory = Files.createTempDirectory("home-cache").toFile()
            try {
                val sources = listOf(server.url("/catalog").toString())
                val first = UnifiedVodRepository(sources, directory, this) { VodCatalogClient(OkHttpClient()) }
                first.browse(VodFilter(VodKind.MOVIE), "")
                withTimeout(3000) { first.state.first { !it.loading && it.items.isNotEmpty() } }
                first.browse(VodFilter(VodKind.SERIES), "")
                withTimeout(3000) { first.state.first { !it.loading && it.items.isNotEmpty() } }
                var calls = 0
                val homeMovies = UnifiedVodRepository(sources, directory, this) { calls++; error("offline") }
                val homeSeries = UnifiedVodRepository(sources, directory, this) { calls++; error("offline") }
                homeMovies.browse(VodFilter(VodKind.MOVIE), "", cacheOnly = true)
                homeSeries.browse(VodFilter(VodKind.SERIES), "", cacheOnly = true)
                val movies = withTimeout(3000) { homeMovies.state.first { !it.loading && it.items.isNotEmpty() } }
                val series = withTimeout(3000) { homeSeries.state.first { !it.loading && it.items.isNotEmpty() } }
                assertEquals("电影甲", movies.items.single().movie.name)
                assertEquals("剧集甲", series.items.single().movie.name)
                assertEquals("剧集甲", first.state.value.items.single().movie.name)
                assertEquals(0, calls)
                assertTrue(movies.showingCache)
                assertTrue(series.items.single().movie.episodes.isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun leavingPageCancelsBrowseAndSubscriptionChangesCannotRestartIt() = runBlocking {
        val directory = Files.createTempDirectory("pause-vod").toFile()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var attempts = 0
        try {
            val repository = UnifiedVodRepository(listOf("https://fixture.test/old"), directory, this) {
                attempts++
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            repository.browse(VodFilter(), "")
            withTimeout(3000) { started.await() }
            repository.setVisible(false)
            withTimeout(3000) { cancelled.await() }
            repository.updateSources(listOf("https://fixture.test/new"))
            repository.refreshDue(0)
            repository.browse(VodFilter(), "late consent callback")
            yield()
            assertEquals(1, attempts)
            assertFalse(repository.state.value.loading)
            repository.setVisible(true)
            repository.browse(VodFilter(), "")
            withTimeout(3000) { while (attempts < 2) delay(10) }
            repository.setVisible(false)
        } finally { directory.deleteRecursively() }
    }
    @Test fun leavingPageKeepsAlreadyLoadedItems() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("retain-vod").toFile()
            try {
                server.enqueue(MockResponse().setBody("""{"page":1,"pagecount":1,"list":[{"vod_id":"1","vod_name":"保留影片","type_name":"动作片"}]}"""))
                val repository = UnifiedVodRepository(listOf(server.url("/vod").toString()), directory, this) { VodCatalogClient(OkHttpClient()) }
                repository.browse(VodFilter(), "")
                withTimeout(3000) { repository.state.first { !it.loading && it.items.isNotEmpty() } }
                repository.setVisible(false)
                repository.refreshDue(0)
                assertEquals("保留影片", repository.state.value.items.single().movie.name)
                assertEquals(1, server.requestCount)
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun removingAllSourcesCancelsBrowseAndClearsOldResults() = runBlocking {
        val directory = Files.createTempDirectory("remove-vod").toFile()
        val started = CompletableDeferred<Unit>()
        try {
            val repository = UnifiedVodRepository(listOf("https://fixture.test/api"), directory, this) {
                started.complete(Unit); awaitCancellation()
            }
            repository.browse(VodFilter(), "")
            withTimeout(3000) { started.await() }
            repository.updateSources(emptyList())
            val state = repository.state.value
            assertFalse(state.loading); assertTrue(state.items.isEmpty()); assertFalse(state.canLoadMore)
            assertTrue(state.notice!!.contains("尚未添加"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun coolingSourceIsSkippedUntilExplicitResetWithoutScanningOtherSources() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("health-browse").toFile()
            try {
                val source = server.url("/vod").toString()
                val health = VodSourceHealth(directory.resolve("health.json"), this)
                health.record(source, "wifi", false); health.record(source, "wifi", false)
                val repository = UnifiedVodRepository(listOf(source), directory.resolve("catalog"), this,
                    health = health, healthContext = { "wifi" }) { VodCatalogClient(OkHttpClient()) }
                repository.browse(VodFilter(), "", force = true)
                val cooled = withTimeout(3000) { repository.state.first { !it.loading } }
                assertTrue(cooled.notice!!.contains("暂缓")); assertEquals(0, server.requestCount)
                health.reset(source, "wifi")
                server.enqueue(MockResponse().setBody("""{"page":1,"pagecount":1,"list":[{"vod_id":"1","vod_name":"恢复影片","type_name":"动作片"}]}"""))
                repository.browse(VodFilter(), "", force = true)
                val good = withTimeout(5000) { repository.state.first { !it.loading && it.completed == 1 } }
                assertEquals("恢复影片", good.items.single().movie.name)
                assertEquals(1, server.requestCount); assertEquals(0, health.get(source, "wifi")!!.failures)
            } finally { directory.deleteRecursively() }
        }
    }

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
    @Test fun matchingMetadataFromOtherShelfAppearsBeforeNetworkAndPlaybackUrlsAreDiscarded() = runBlocking {
        val directory = Files.createTempDirectory("cross-filter-shelf").toFile()
        val source = "https://fixture.test/api"
        java.io.File(directory, "shelf-another-filter.json").writeText("""{"updated":${System.currentTimeMillis()},"offers":[{"source":"$source","movie":{"id":"1","name":"缓存电影","category":"动作片","episodes":[]}}]}""")
        val repository = UnifiedVodRepository(listOf(source), directory, this) { awaitCancellation() }
        try {
            repository.browse(VodFilter(genre = "动作"), "")
            val cached = withTimeout(3000) { repository.state.first { it.loading && it.items.isNotEmpty() } }
            assertTrue(cached.showingCache)
            assertEquals("缓存电影", cached.items.single().movie.name)
            assertTrue(cached.items.single().movie.episodes.isEmpty())
            repository.browse(VodFilter(kind = VodKind.SHORT_DRAMA), "")
            delay(100)
            assertTrue(repository.state.value.items.isEmpty())
        } finally { repository.setVisible(false); directory.deleteRecursively() }
    }

}
