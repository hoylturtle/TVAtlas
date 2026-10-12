package com.tvatlas.player.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableCallTest {
    @Test fun cancelClosesStalledResponseWithoutWaitingForSocketTimeout() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url(server.url("/slow")).build())
        try {
            val job = launch(Dispatchers.Default) { call.readCancellable { it.body!!.string() } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertEquals(0, client.dispatcher.runningCallsCount())
        } finally { client.dispatcher.cancelAll(); server.shutdown() }
    }
}
