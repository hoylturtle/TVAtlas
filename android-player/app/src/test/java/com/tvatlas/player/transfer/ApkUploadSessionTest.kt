package com.tvatlas.player.transfer

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

class ApkUploadSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private fun session(limit: Long = 1024, inspect: (java.io.File) -> UploadedApk = {
        UploadedApk(it, "Fixture", "com.fixture", "1.0")
    }) = ApkUploadSession(InetAddress.getByName("127.0.0.1"), temporary.root, inspect, maximumBytes = limit)
    private fun upload(session: ApkUploadSession, bytes: ByteArray = byteArrayOf(1, 2, 3), name: String = "fixture.apk",
        origin: String? = null) = client.newCall(Request.Builder().url(session.pageUrl + "/upload")
        .header("X-File-Name", name).apply { origin?.let { header("Origin", it) } }.post(bytes.toRequestBody()).build()).execute()
    @Test fun streamsExactBytesAndKeepsValidatedFileForInstallerAfterClose() {
        val session = session()
        session.use {
            client.newCall(Request.Builder().url(it.pageUrl).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertTrue(response.body!!.string().contains("onprogress"))
                assertTrue(response.header("Content-Security-Policy")!!.contains("connect-src 'self'"))
            }
            upload(it, name = "..%2F..%2Ffixture.apk").use { response -> assertEquals(200, response.code) }
            val ready = it.state.value.ready!!
            assertArrayEquals(byteArrayOf(1, 2, 3), ready.file.readBytes())
            assertEquals("received.apk", ready.file.name)
            assertEquals(3L, it.state.value.received)
            upload(it).use { response -> assertEquals(409, response.code) }
            assertEquals(ready, it.state.value.ready)
        }
        assertTrue(session.state.value.ready!!.file.exists())
        assertFalse(session.active.value)
    }
    @Test fun rejectsOversizedWrongTypeAndCrossOriginUploads() {
        session(limit = 2).use {
            upload(it).use { response -> assertEquals(413, response.code) }
            upload(it, byteArrayOf(1), "fixture.xapk").use { response -> assertEquals(415, response.code) }
            upload(it, byteArrayOf(1), origin = "https://other.example").use { response -> assertEquals(403, response.code) }
            assertNull(it.state.value.ready)
            assertTrue(temporary.root.walkTopDown().none { file -> file.isFile })
        }
    }
    @Test fun invalidArchiveCanBeRetriedWithoutKeepingPartialFiles() {
        var calls = 0
        session(inspect = {
            if (++calls == 1) error("invalid fixture")
            UploadedApk(it, "Fixture", "com.fixture", "1")
        }).use {
            upload(it).use { response -> assertEquals(422, response.code) }
            assertNull(it.state.value.ready)
            upload(it).use { response -> assertEquals(200, response.code) }
            assertNotNull(it.state.value.ready)
        }
    }
    @Test fun incompleteUploadDoesNotProduceAnInstallableFile() {
        session().use {
            val uri = URI(it.pageUrl)
            Socket(uri.host, uri.port).use { socket ->
                socket.getOutputStream().write(("POST ${uri.path}/upload HTTP/1.1\r\nHost: ${uri.authority}\r\n" +
                    "Content-Length: 100\r\nX-File-Name: fixture.apk\r\n\r\na").toByteArray())
                socket.shutdownOutput()
                socket.getInputStream().readBytes()
            }
            assertNull(it.state.value.ready)
            assertFalse(it.state.value.receiving)
        }
        val deadline = System.nanoTime() + 1_000_000_000
        while (temporary.root.walkTopDown().any { it.isFile } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(temporary.root.walkTopDown().none { it.isFile })
    }
    @Test fun wrongTokenAndHostCannotUpload() {
        session().use {
            val request = Request.Builder().url(it.pageUrl + "/upload").header("Host", "other.example")
                .header("X-File-Name", "fixture.apk").post(byteArrayOf(1).toRequestBody()).build()
            client.newCall(request).execute().use { response -> assertEquals(404, response.code) }
            val wrong = Request.Builder().url(it.pageUrl.substringBeforeLast('/') + "/wrong/upload")
                .header("X-File-Name", "fixture.apk").post(byteArrayOf(1).toRequestBody()).build()
            client.newCall(wrong).execute().use { response -> assertEquals(404, response.code) }
            assertNull(it.state.value.ready)
        }
    }
}
