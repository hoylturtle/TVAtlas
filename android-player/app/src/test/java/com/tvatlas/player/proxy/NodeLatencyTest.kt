package com.tvatlas.player.proxy

import com.tvatlas.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NodeLatencyTest {
    @Test fun batchReportsActiveNodesCompletedFailuresAndSkipsDisabledNodes() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(if (request.getHeader("X-Test-Node") == "p0") 200 else 204)
                        .setHeadersDelay(100, TimeUnit.MILLISECONDS)
            }
            val tester = NodeLatencyTester({ target -> OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Test-Node", target.profile ?: "").build())
            }.build() }, server.url("/generate_204").toString())
            val profiles = (0..5).map { ProxyProfile("p$it", "节点$it", ProxyType.HTTP, "localhost", 80) }
            val disabled = ProxyProfile("disabled", "停用", ProxyType.HTTP, "localhost", 80, enabled = false)
            val job = launch { tester.measure(profiles + profiles.first() + disabled) }
            try {
                val active = withTimeout(5000) { tester.progress.first { it.activeIds.size == 4 } }
                assertEquals(6, active.total)
                assertTrue(active.running)
                job.join()
                val done = tester.progress.value
                assertEquals(6, done.completed)
                assertEquals(5, done.succeeded)
                assertEquals(1, done.failed)
                assertEquals(0, done.remaining)
                assertTrue(done.activeIds.isEmpty())
                assertFalse(done.running)
                assertFalse(tester.health.value.containsKey("disabled"))
            } finally { job.cancelAndJoin() }
        }
    }
    @Test fun cancellationPreservesIncompleteCountAndClearsActiveNodes() = runBlocking {
        MockWebServer().use { server ->
            repeat(12) { server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(1, TimeUnit.SECONDS)) }
            val tester = NodeLatencyTester({ OkHttpClient() }, server.url("/generate_204").toString())
            val job = launch { tester.measure((0..5).map { ProxyProfile("p$it", "节点$it", ProxyType.HTTP, "localhost", 80) }) }
            withTimeout(5000) { tester.progress.first { it.activeIds.size == 4 } }
            job.cancelAndJoin()
            assertFalse(tester.testing.value)
            assertFalse(tester.progress.value.running)
            assertTrue(tester.progress.value.activeIds.isEmpty())
            assertTrue(tester.progress.value.remaining > 0)
            tester.invalidate()
            assertEquals(NodeTestProgress(), tester.progress.value)
        }
    }
    @Test fun measuresActualResponseAndRejectsPortalThenReusesFreshSample() = runBlocking {
        MockWebServer().use { server ->
            val tester = NodeLatencyTester({ OkHttpClient() }, server.url("/generate_204").toString())
            val profiles = listOf(ProxyProfile("hk", "香港", ProxyType.HTTP, "localhost", 80))
            repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
            tester.measure(profiles)
            assertNotNull(tester.health.value["hk"]!!.delayMs)
            tester.measure(profiles, staleOnly = true)
            assertEquals(3, server.requestCount)
            tester.invalidate()
            repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("portal")) }
            tester.measure(profiles)
            assertNull(tester.health.value["hk"]!!.delayMs)
            assertFalse(tester.testing.value)
        }
    }
    @Test fun oneBrokenClientDoesNotAbortQueuedNodesAndSubscriptionNoticesAreExcluded() = runBlocking {
        MockWebServer().use { server ->
            repeat(18) { server.enqueue(MockResponse().setResponseCode(204)) }
            val tester = NodeLatencyTester({ target ->
                if (target.profile == "bad") throw IllegalArgumentException("broken fixture")
                OkHttpClient()
            }, server.url("/generate_204").toString())
            val nodes = (0..5).map { ProxyProfile("p$it", "节点$it", ProxyType.HTTP, "localhost", 80) }
            tester.measure(nodes + nodes.first().copy(id = "bad") + nodes.first().copy(id = "notice", name = "到期：2027-01-15", subscriptionId = "fixture"))
            assertEquals(7, tester.progress.value.total)
            assertEquals(7, tester.progress.value.completed)
            assertEquals(1, tester.progress.value.failed)
            assertEquals(0, tester.progress.value.remaining)
            assertFalse(tester.health.value.containsKey("notice"))
        }
    }

}
