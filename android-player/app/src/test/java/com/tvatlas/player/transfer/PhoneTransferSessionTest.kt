package com.tvatlas.player.transfer
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
class PhoneTransferSessionTest {
    private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private fun session(ttl: Long = 300_000) = PhoneTransferSession(InetAddress.getByName("127.0.0.1"), TransferKind.PROXY, ttl)
    private fun post(url: String, value: String, origin: String? = null): Int {
        val builder = Request.Builder().url(url).post(FormBody.Builder().add("name", "我的订阅").add("url", value).build())
        if (origin != null) builder.header("Origin", origin)
        return client.newCall(builder.build()).execute().use { it.code }
    }
    @Test fun localPageReceivesExactUrlOnceWithoutEchoingIt() {
        session().use { transfer ->
            client.newCall(Request.Builder().url(transfer.pageUrl).build()).execute().use {
                assertEquals(200, it.code); assertTrue(it.body!!.string().contains("发送到电视"))
                assertEquals("no-store", it.header("Cache-Control"))
            }
            val value = "https://example.com/sub?token=fixture%2Btoken&format=clash+yaml"
            assertEquals(200, post(transfer.pageUrl, "  $value\n"))
            await { transfer.state.value.received != null }
            assertEquals(TransferInput("我的订阅", value), transfer.state.value.received)
            assertFalse(transfer.state.value.active)
            assertTrue(runCatching { post(transfer.pageUrl, "https://example.com/other") }.isFailure)
            assertEquals(value, transfer.state.value.received!!.url)
        }
    }
    @Test fun wrongTokenAndCrossOriginAndInvalidUrlsCannotFillTelevision() {
        session().use { transfer ->
            val origin = transfer.pageUrl.substringBeforeLast('/')
            assertEquals(404, post("$origin/wrong-token", "https://example.com/a"))
            assertEquals(403, post(transfer.pageUrl, "https://example.com/a", "https://untrusted.example"))
            for (value in listOf("file:///sdcard/config", "https://name:password@example.com/a", "not a url"))
                assertEquals(400, post(transfer.pageUrl, value))
            assertNull(transfer.state.value.received)
            assertTrue(transfer.state.value.active)
            assertEquals(200, post(transfer.pageUrl, "https://example.com/a"))
        }
    }
    @Test fun expirationAndCancellationCloseListener() {
        session(30).use { transfer ->
            await { !transfer.state.value.active }
            assertNull(transfer.state.value.received)
            assertTrue(transfer.state.value.error!!.contains("过期"))
            assertTrue(runCatching { post(transfer.pageUrl, "https://example.com/a") }.isFailure)
        }
        val cancelled = session(); cancelled.close()
        assertFalse(cancelled.state.value.active)
        assertTrue(runCatching { post(cancelled.pageUrl, "https://example.com/a") }.isFailure)
    }
    @Test fun cloudTransferAcceptsPastedShareTextButRejectsUnrelatedLinks() {
        PhoneTransferSession(InetAddress.getByName("127.0.0.1"), TransferKind.CLOUD).use { transfer ->
            assertEquals(400, post(transfer.pageUrl, "https://example.com/not-a-cloud-share"))
            val pasted = "给你分享了「测试」 链接：https://pan.quark.cn/s/abcdef123456?pwd=aB12 提取码：aB12"
            val request = Request.Builder().url(transfer.pageUrl).post(FormBody.Builder().add("name", "测试分享").add("url", pasted).build()).build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                val acknowledgement = response.body!!.string()
                assertFalse(acknowledgement.contains("abcdef123456")); assertFalse(acknowledgement.contains("aB12"))
            }
            await { transfer.state.value.received != null }
            assertEquals(pasted, transfer.state.value.received!!.url)
            assertFalse(transfer.state.value.active)
        }
    }
    @Test fun videoTransferRejectsSharePagesAndDoesNotEchoTemporaryToken() {
        PhoneTransferSession(InetAddress.getByName("127.0.0.1"), TransferKind.VIDEO).use { transfer ->
            assertEquals(400, post(transfer.pageUrl, "https://pan.quark.cn/s/example"))
            assertNull(transfer.state.value.received)
            val value = "https://media.example/video.mp4?token=private-fixture"
            client.newCall(Request.Builder().url(transfer.pageUrl).post(FormBody.Builder()
                .add("name", "测试视频").add("url", value).build()).build()).execute().use {
                assertEquals(200, it.code)
                assertFalse(it.body!!.string().contains("private-fixture"))
            }
            await { transfer.state.value.received != null }
            assertEquals(value, transfer.state.value.received!!.url)
            assertFalse(transfer.state.value.active)
        }
    }
    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000
        while (!predicate() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(predicate())
    }
}
