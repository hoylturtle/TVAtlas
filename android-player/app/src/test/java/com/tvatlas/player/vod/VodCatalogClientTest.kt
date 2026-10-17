package com.tvatlas.player.vod

import com.tvatlas.player.debug.DebugLog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VodCatalogClientTest {
    @Test fun followsRedirectsEvenWhenBaseClientDisablesThemAndLogsWithoutQueries() = runBlocking {
        val server = MockWebServer()
        val folder = Files.createTempDirectory("vod-log").toFile()
        val log = DebugLog(java.io.File(folder, "player.log"), "test")
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/catalog?secret=private-value"))
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":1,"vod_name":"movie","vod_play_url":"HD${'$'}https://media.example/a.m3u8"}]}"""))
            val client = VodCatalogClient(OkHttpClient.Builder().followRedirects(false).build(), log)
            val page = client.load(server.url("/api").toString(), 1, "private-search")
            assertEquals(1, page.movies.size)
            assertEquals(1, page.movies.single().episodes.size)
            assertTrue(log.snapshot().contains("VOD_CATALOG_HTTP"))
            assertFalse(log.snapshot().contains("private-value"))
            assertFalse(log.snapshot().contains("private-search"))
        } finally { server.shutdown(); log.close(); folder.deleteRecursively() }
    }
    @Test fun reportsHttpFailureInsteadOfTryingToParseErrorHtml() = runBlocking {
        val server = MockWebServer()
        try {
            server.start(); server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
            try { VodCatalogClient(OkHttpClient()).load(server.url("/api").toString(), 1, ""); fail("Must report HTTP 403") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("HTTP 403")) }
        } finally { server.shutdown() }
    }
    @Test fun mediaFollowsCdnRedirectAndRecordsFailedStatus() {
        val server = MockWebServer()
        val folder = Files.createTempDirectory("vod-media-log").toFile()
        val log = DebugLog(java.io.File(folder, "player.log"), "test")
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/segment.ts?token=secret-value"))
            server.enqueue(MockResponse().setResponseCode(403))
            val client = vodMediaClient(OkHttpClient.Builder().followRedirects(false).build(), { true }, log)
            client.newCall(okhttp3.Request.Builder().url(server.url("/video.m3u8")).build()).execute().use {
                assertEquals(403, it.code)
            }
            assertEquals(2, server.requestCount)
            assertTrue(log.snapshot().contains("status=403"))
            assertFalse(log.snapshot().contains("secret-value"))
        } finally { server.shutdown(); log.close(); folder.deleteRecursively() }
    }

}
