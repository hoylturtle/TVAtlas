package com.tvatlas.player.storage

import com.tvatlas.core.model.*
import com.tvatlas.core.subscription.SubscriptionException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class SubscriptionDownloaderTest {
    @Test fun autoPlanPrefersHongKongButExplicitNetworkHasNoSilentFallback() {
        val nodes = listOf(ProxyProfile("us", "美国", ProxyType.HTTP, "localhost", 80),
            ProxyProfile("hk", "香港", ProxyType.HTTP, "localhost", 80),
            ProxyProfile("off", "香港停用", ProxyType.HTTP, "localhost", 80, enabled = false))
        assertEquals(listOf(RouteTarget.DIRECT, RouteTarget.proxy("hk"), RouteTarget.proxy("us")), subscriptionTargets(RouteTarget.AUTO, nodes))
        assertEquals(listOf(RouteTarget.proxy("hk")), subscriptionTargets(RouteTarget.proxy("hk"), nodes))
    }
    @Test fun transportFailureRetriesNextNetworkAndUsesStandardClashAgent() = runBlocking {
        val server = MockWebServer().apply { start() }
        val visited = mutableListOf<RouteTarget>()
        try {
            server.enqueue(MockResponse().setBody("proxies: []"))
            val downloader = SubscriptionDownloader({ target ->
                visited.add(target)
                if (target == RouteTarget.DIRECT) throw UnknownHostException("private.example?token=secret")
                OkHttpClient()
            })
            assertEquals("proxies: []", downloader.download(server.url("/private?token=fixture").toString(), listOf(RouteTarget.DIRECT, RouteTarget.proxy("hk"))))
            assertEquals(2, visited.size)
            assertEquals("ClashMeta/1.19.32", server.takeRequest().getHeader("User-Agent"))
        } finally { server.shutdown() }
    }
    @Test fun forbiddenDoesNotRetryAndServerFailureCanRetry() = runBlocking {
        val server = MockWebServer().apply { start() }
        val downloader = SubscriptionDownloader({ OkHttpClient() })
        val targets = listOf(RouteTarget.DIRECT, RouteTarget.proxy("hk"))
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("private-token"))
            try { downloader.download(server.url("/").toString(), targets); fail() }
            catch (e: SubscriptionException) { assertTrue(e.message!!.contains("HTTP 403")); assertFalse(e.message!!.contains("private-token")) }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("proxies: []"))
            assertEquals("proxies: []", downloader.download(server.url("/").toString(), targets))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun readsSubscriptionQuotaWithoutExposingSubscriptionAddress() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("proxies: []")
                .addHeader("subscription-userinfo", "upload=1024; download=3072; total=10240; expire=1791763200"))
            val result = SubscriptionDownloader({ OkHttpClient() }).downloadWithMetadata(
                server.url("/private?token=fixture").toString(), listOf(RouteTarget.DIRECT))
            assertEquals("proxies: []", result.text)
            assertEquals(4096L, result.quota?.usedBytes)
            assertEquals(6144L, result.quota?.remainingBytes)
            assertEquals(1791763200000L, result.quota?.expireAt)
        } finally { server.shutdown() }
    }
    @Test fun malformedOrMissingQuotaIsTreatedAsUnavailable() {
        assertNull(parseSubscriptionQuota(null))
        assertNull(parseSubscriptionQuota("upload=1; download=2"))
        assertNull(parseSubscriptionQuota("upload=nope; download=2; total=10"))
    }
}
