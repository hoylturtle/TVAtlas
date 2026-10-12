package com.tvatlas.player.proxy

import com.tvatlas.core.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NodeLatencyTest {
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
}
