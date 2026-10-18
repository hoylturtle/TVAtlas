package com.tvatlas.player.transfer

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class LogShareSessionTest {
    private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private fun session(text: String = "启动 begin\nready", ttl: Long = 300_000) = LogShareSession(InetAddress.getByName("127.0.0.1"), text, ttl)
    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()
    @Test fun phoneCanViewCopyAndDownloadExactSnapshot() {
        val log = "启动 <script>alert('fixture')</script> & <日志>"
        session(log).use { share ->
            get(share.pageUrl).use {
                assertEquals(200, it.code)
                assertEquals("no-store", it.header("Cache-Control"))
                val html = it.body!!.string()
                assertTrue(html.contains("复制日志")); assertTrue(html.contains("下载 TXT"))
                assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>alert"))
                assertTrue(it.header("Content-Security-Policy")!!.contains("frame-ancestors 'none'"))
            }
            for (path in listOf("/log.txt", "/download")) get(share.pageUrl + path).use {
                assertEquals(200, it.code); assertEquals(log, it.body!!.string())
                if (path == "/download") assertTrue(it.header("Content-Disposition")!!.contains("attachment"))
            }
        }
    }
    @Test fun noOtherFilesTokensHostsOrMutationRequestsAreAllowed() {
        session().use { share ->
            get(share.pageUrl.substringBeforeLast('/') + "/wrong").use { assertEquals(404, it.code) }
            get(share.pageUrl + "/other.txt").use { assertEquals(404, it.code) }
            client.newCall(Request.Builder().url(share.pageUrl).header("Host", "attacker.example").build()).execute().use { assertEquals(404, it.code) }
            client.newCall(Request.Builder().url(share.pageUrl).header("Origin", "https://attacker.example").build()).execute().use { assertEquals(403, it.code) }
            client.newCall(Request.Builder().url(share.pageUrl).post("bad".toRequestBody()).build()).execute().use { assertEquals(405, it.code) }
            get(share.pageUrl + "/log.txt").use { assertEquals(200, it.code) }
        }
    }
    @Test fun expiryAndCloseDisableAccess() {
        session(ttl = 20).use { share ->
            val deadline = System.nanoTime() + 2_000_000_000
            while (share.active.value && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(share.active.value)
            assertTrue(runCatching { get(share.pageUrl).close() }.isFailure)
        }
        val share = session(); share.close()
        assertFalse(share.active.value)
        assertTrue(runCatching { get(share.pageUrl).close() }.isFailure)
    }
}
