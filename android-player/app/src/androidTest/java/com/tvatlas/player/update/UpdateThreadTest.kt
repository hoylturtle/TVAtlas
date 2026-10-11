package com.tvatlas.player.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateThreadTest {
    @Test fun checkStartedFromUiActuallyReachesServerInsteadOfFailingMainThreadPolicy() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(MockResponse().setBody("[]")) }
        try {
            withContext(Dispatchers.Main) {
                try {
                    UpdateRepository(OkHttpClient(), api = server.url("/repo").toString()).latestAsync()
                    fail("No release expected")
                } catch (error: UpdateException) {
                    assertTrue(error.message!!.contains("暂未找到"))
                }
            }
            assertEquals(1, server.requestCount)
            assertEquals("/repo/releases?per_page=10", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
