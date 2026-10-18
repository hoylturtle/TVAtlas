package com.tvatlas.player.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.player.update.withUpdateRoutes
import org.junit.Assert.*
import org.junit.Test

class ResourceFetchTest {
    @Test fun followsRelativeRedirectAndKeepsSameOriginHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next?token=private"))
            server.enqueue(MockResponse().setBody("contents"))
            val bytes = fetchResourceBytes(OkHttpClient(), Request.Builder().url(server.url("/start")).header("Cookie", "LANG=zh").build(), 100)
            assertEquals("contents", bytes.toString(Charsets.UTF_8))
            server.takeRequest()
            assertEquals("LANG=zh", server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun crossOriginRedirectRemovesCredentials() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { next ->
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", next.url("/file")))
            next.enqueue(MockResponse().setBody("ok"))
            fetchResourceBytes(OkHttpClient(), Request.Builder().url(first.url("/start")).header("Authorization", "secret").header("Cookie", "private").build(), 100)
            val request = next.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        } }
    }
    @Test fun upgradeAllowedDowngradeAndCredentialsBlocked() {
        val from = "http://example.test/file".toHttpUrl()
        assertEquals("https", resourceRedirect(from, "https://example.test/file", setOf(from), 0).scheme)
        for (pair in listOf("https://example.test/file" to "http://example.test/file", "http://example.test/file" to "https://user:secret@example.test/file")) {
            try { resourceRedirect(pair.first.toHttpUrl(), pair.second, emptySet(), 0); fail("must reject") }
            catch (_: ResourceRedirectException) { }
        }
    }
    @Test fun redirectLoopDoesNotCycleThroughProxyNodes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/start"))
            var routes = 0
            try {
                withUpdateRoutes(listOf(RouteTarget.DIRECT, RouteTarget.proxy("backup")), false, client = { routes++; OkHttpClient() }) {
                    fetchResourceBytes(it, Request.Builder().url(server.url("/start")).build(), 100)
                }
                fail("must reject loop")
            } catch (_: ResourceRedirectException) { }
            assertEquals(1, routes); assertEquals(1, server.requestCount)
        }
    }
    @Test fun decompressedSizeIsBounded() = runBlocking {
        MockWebServer().use { server ->
            val compressed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(ByteArray(1000)) } }.toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(compressed)))
            try { fetchResourceBytes(OkHttpClient(), Request.Builder().url(server.url("/file")).build(), 100); fail("must reject") }
            catch (_: java.io.IOException) { }
        }
    }
}
