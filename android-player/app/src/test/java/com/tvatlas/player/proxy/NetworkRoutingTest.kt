package com.tvatlas.player.proxy

import com.tvatlas.core.model.*
import com.tvatlas.core.routing.*
import com.tvatlas.player.storage.Credentials
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkRoutingTest {
    @Test fun redirectIsReevaluatedBeforeNextConnection() {
        val origin = MockWebServer()
        val proxy = MockWebServer()
        origin.start(); proxy.start()
        val profile = ProxyProfile("HK", "test", ProxyType.HTTP, "127.0.0.1", proxy.port)
        val pool = ProxyClientPool { null }.apply { update(listOf(profile)) }
        try {
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://cdn.example.test/segment.ts"))
            proxy.enqueue(MockResponse().setBody("segment"))
            val stream = Stream("s", origin.url("/master.m3u8").toString(), "p")
            val context = StreamContext(Channel("c", "test", "test", streams = listOf(stream)), stream)
            val resolver = DefaultRouteResolver(RuleConfig(rules = listOf(
                Rule("cdn", Match(domain = "cdn.example.test"), RouteTarget.proxy("HK")))), listOf(profile))
            val factory = RoutedCallFactory(pool, resolver, context, RouteTarget.DIRECT)
            factory.newCall(Request.Builder().url(stream.url).header("Authorization", "private").build()).execute().use {
                assertEquals("segment", it.body!!.string())
            }
            val request = proxy.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(request.requestLine.contains("http://cdn.example.test/segment.ts"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Proxy-Authorization"))
        } finally { pool.close(); origin.shutdown(); proxy.shutdown() }
    }

    @Test fun childMediaRequestInheritsProxy() {
        val proxy = MockWebServer().apply { start() }
        val profile = ProxyProfile("US", "test", ProxyType.HTTP, "127.0.0.1", proxy.port)
        val pool = ProxyClientPool { null }.apply { update(listOf(profile)) }
        try {
            proxy.enqueue(MockResponse().setBody("key"))
            val stream = Stream("s", "http://origin.example.test/master.m3u8", "p")
            val context = StreamContext(Channel("c", "test", "test", streams = listOf(stream)), stream)
            val factory = RoutedCallFactory(pool, DefaultRouteResolver(RuleConfig(), listOf(profile)), context, RouteTarget.proxy("US"))
            factory.newCall(Request.Builder().url("http://key.example.test/key").build()).execute().use {
                assertEquals("key", it.body!!.string())
            }
            assertTrue(proxy.takeRequest(5, TimeUnit.SECONDS)!!.requestLine.contains("http://key.example.test/key"))
        } finally { pool.close(); proxy.shutdown() }
    }

    @Test fun socks5CredentialsAndDnsStayPerProfile() {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<List<String>> {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream())
                val out = socket.getOutputStream()
                assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte()); assertEquals(2, input.readUnsignedByte())
                out.write(byteArrayOf(5, 2)); out.flush()
                assertEquals(1, input.readUnsignedByte())
                val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                out.write(byteArrayOf(1, 0)); out.flush()
                assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                assertEquals(3, input.readUnsignedByte())
                val host = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                assertEquals(443, input.readUnsignedShort())
                out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); out.flush()
                listOf(user, pass, host)
            }
        }
        try {
            val profile = ProxyProfile("US", "test", ProxyType.SOCKS5, "127.0.0.1", server.localPort)
            Socks5SocketFactory(profile, Credentials("local-user", "local-password")).createSocket().use {
                it.connect(InetSocketAddress.createUnresolved("remote.example.test", 443), 5000)
            }
            assertEquals(listOf("local-user", "local-password", "remote.example.test"), result.get(5, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }
}
