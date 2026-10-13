package com.tvatlas.player.traffic

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TrafficTest {
    @Test fun routeAttributionCountsUploadDownloadAndFallbackWithoutDoubleCounting() = runBlocking {
        val dir = Files.createTempDirectory("traffic").toFile()
        val store = TrafficStore(dir.resolve("usage.json"))
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).setBody("failed"))
                server.enqueue(MockResponse().setBody("成功"))
                val direct = OkHttpClient.Builder().addNetworkInterceptor(TrafficInterceptor(store, "DIRECT", "直连", false)).build()
                val proxy = OkHttpClient.Builder().addNetworkInterceptor(TrafficInterceptor(store, "PROXY:hk", "香港一", true)).build()
                direct.newCall(Request.Builder().url(server.url("/first")).post("abc".toRequestBody()).build()).execute().use { it.body!!.string() }
                proxy.newCall(Request.Builder().url(server.url("/fallback")).build()).execute().use { assertEquals("成功", it.body!!.string()) }
                store.flush()
                val saved = kotlinx.serialization.json.Json.decodeFromString<TrafficSnapshot>(dir.resolve("usage.json").readText())
                assertEquals(9L, saved.direct); assertEquals(6L, saved.proxy)
                assertEquals(3L, saved.entries.single { !it.proxy }.sent)
                assertEquals("香港一", saved.entries.single { it.proxy }.name)
                assertEquals(2, server.requestCount)
            }
        } finally { store.shutdown(); dir.deleteRecursively() }
    }
    @Test fun gzipCountsCompressedNetworkBodyRatherThanExpandedContent() = runBlocking {
        val dir = Files.createTempDirectory("traffic-gzip").toFile(); val store = TrafficStore(dir.resolve("usage.json"))
        try {
            MockWebServer().use { server ->
                val buffer = Buffer(); val text = "large repeated content".repeat(500)
                GzipSink(buffer).buffer().use { it.writeUtf8(text) }; val compressed = buffer.size
                server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(buffer))
                val client = OkHttpClient.Builder().addNetworkInterceptor(TrafficInterceptor(store, "DIRECT", "直连", false)).build()
                client.newCall(Request.Builder().url(server.url("/gzip")).build()).execute().use { assertEquals(text, it.body!!.string()) }
                store.flush()
                val snapshot = kotlinx.serialization.json.Json.decodeFromString<TrafficSnapshot>(dir.resolve("usage.json").readText())
                assertEquals(compressed, snapshot.direct); assertTrue(compressed < text.length)
            }
        } finally { store.shutdown(); dir.deleteRecursively() }
    }
    @Test fun cancelledOrClosedBodyRetainsOnlyTransferredBytes() = runBlocking {
        val dir = Files.createTempDirectory("traffic-partial").toFile(); val store = TrafficStore(dir.resolve("usage.json"))
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("x".repeat(200000)))
                val client = OkHttpClient.Builder().addNetworkInterceptor(TrafficInterceptor(store, "PROXY:tw", "台湾", true)).build()
                val call = client.newCall(Request.Builder().url(server.url("/partial")).build())
                call.execute().use { response -> response.body!!.source().readByte(); call.cancel() }
                store.flush()
                val snapshot = kotlinx.serialization.json.Json.decodeFromString<TrafficSnapshot>(dir.resolve("usage.json").readText())
                assertTrue(snapshot.proxy in 1L until 200000L)
            }
        } finally { store.shutdown(); dir.deleteRecursively() }
    }
    @Test fun persistedCountersMergeStartupTrafficAndResetSurvivesRestart() = runBlocking {
        val dir = Files.createTempDirectory("traffic-persist").toFile(); val file = dir.resolve("usage.json")
        try {
            val first = TrafficStore(file)
            first.record("DIRECT", "直连", false, received = 100)
            first.record("PROXY:hk", "香港", true, received = 200, sent = 20)
            first.shutdown()
            val second = TrafficStore(file)
            second.record("PROXY:hk", "香港改名", true, received = 50)
            second.flush()
            val saved = kotlinx.serialization.json.Json.decodeFromString<TrafficSnapshot>(file.readText())
            assertEquals(100L, saved.direct); assertEquals(270L, saved.proxy)
            assertEquals("香港改名", saved.entries.single { it.proxy }.name)
            second.reset(); second.record("DIRECT", "直连", false, received = 3); second.shutdown()
            val third = TrafficStore(file); third.flush()
            assertEquals(3L, third.state.value.direct); assertEquals(0L, third.state.value.proxy)
            third.shutdown()
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidLocalFileDoesNotPreventFreshStatistics() = runBlocking {
        val dir = Files.createTempDirectory("traffic-invalid").toFile(); val file = dir.resolve("usage.json").apply { writeText("broken") }
        val store = TrafficStore(file)
        try {
            store.record("DIRECT", "直连", false, received = 8)
            store.flush()
            assertEquals(8L, kotlinx.serialization.json.Json.decodeFromString<TrafficSnapshot>(file.readText()).direct)
        } finally { store.shutdown(); dir.deleteRecursively() }
    }
}
