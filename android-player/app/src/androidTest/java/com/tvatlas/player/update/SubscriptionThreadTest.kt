package com.tvatlas.player.update

import android.os.Looper
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.player.storage.SubscriptionDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SubscriptionThreadTest {
    @Test fun subscriptionDownloadAndClientPreparationStayOffMainThread() = runBlocking {
        val server = MockWebServer().apply { start() }
        val url = server.url("/subscription").toString()
        try {
            server.enqueue(MockResponse().setBody("proxies: []"))
            val download = SubscriptionDownloader({
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                OkHttpClient()
            })
            val text = withContext(Dispatchers.Main) { download.download(url, listOf(RouteTarget.DIRECT)) }
            assertEquals("proxies: []", text)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
