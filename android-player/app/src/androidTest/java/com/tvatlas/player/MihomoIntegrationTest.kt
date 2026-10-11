package com.tvatlas.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.tvatlas.core.model.*
import com.tvatlas.core.subscription.*
import com.tvatlas.player.proxy.*
import com.tvatlas.player.storage.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MihomoIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun subscriptionImportRefreshAndDeleteUseNativeValidationAndKeepStableIds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).build()
        val vault = CredentialVault(context)
        val core = MihomoRuntime(context, vault)
        val server = MockWebServer().apply { start() }
        val repo = SubscriptionRepository(db, OkHttpClient(), vault, core)
        val yaml = """
            proxies:
              - {name: 香港, type: ss, server: 127.0.0.1, port: 18001, cipher: aes-128-gcm, password: fixture-password}
              - {name: VMess, type: vmess, server: 127.0.0.1, port: 18002, uuid: 11111111-1111-1111-1111-111111111111, alterId: 0, cipher: auto, tls: true}
              - {name: VLESS, type: vless, server: 127.0.0.1, port: 18003, uuid: 22222222-2222-2222-2222-222222222222, tls: true}
              - {name: Trojan, type: trojan, server: 127.0.0.1, port: 18004, password: fixture-password, tls: true}
            """.trimIndent()
        val url = server.url("/private-subscription?token=fixture").toString()
        val id = stableId(url)
        try {
            server.enqueue(MockResponse().setBody(yaml))
            repo.add("fixture", url)
            val old = db.dao().subscriptionNodes(id).map { it.id }
            assertEquals(4, old.size)
            server.enqueue(MockResponse().setBody(yaml.replace("fixture-password", "updated-fixture")))
            repo.refresh(id)
            assertEquals(old, db.dao().subscriptionNodes(id).map { it.id })
            server.enqueue(MockResponse().setBody("proxies: [invalid"))
            try { repo.refresh(id); fail("Expected invalid subscription") } catch (_: SubscriptionException) { }
            assertEquals(old, db.dao().subscriptionNodes(id).map { it.id })
            repo.remove(id)
            assertNull(db.dao().subscription(id))
            assertTrue(db.dao().subscriptionNodes(id).isEmpty())
            assertNull(vault.secret("subscription:$id:url"))
            old.forEach { assertNull(vault.secret("node:$it")) }
        } finally { core.close(); db.close(); server.shutdown() }
    }

    @Test fun embeddedCoreStartsAndForwardsThroughPerNodeAuthenticatedSocks() {
        val vault = CredentialVault(context)
        val core = MihomoRuntime(context, vault)
        val upstream = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<String> {
            upstream.accept().use { socket ->
                socket.soTimeout = 10000
                val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val connect = reader.readLine()
                while (!reader.readLine().isNullOrEmpty()) { }
                assertTrue(connect.startsWith("CONNECT "))
                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); socket.getOutputStream().flush()
                val request = reader.readLine()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nfixture".toByteArray()); socket.getOutputStream().flush()
                request
            }
        }
        val parsed = ClashSubscription.parse("proxies:\n  - {name: fixture, type: http, server: 127.0.0.1, port: ${upstream.localPort}}", "fixture-core")
        val node = parsed.nodes.single()
        val pool = ProxyClientPool(vault, core)
        try {
            vault.putSecret("node:${node.profile.id}", node.config.toString())
            core.update(listOf(node.profile)); pool.update(listOf(node.profile))
            val client = pool.client(RouteTarget.proxy(node.profile.id))
            client.newCall(Request.Builder().url("http://remote.example.invalid/stream").build()).execute().use {
                assertEquals("fixture", it.body!!.string())
            }
            assertTrue(result.get(10, TimeUnit.SECONDS).startsWith("GET /stream"))
        } finally { pool.close(); core.close(); vault.putSecret("node:${node.profile.id}", null); upstream.close(); executor.shutdownNow() }
    }
}
