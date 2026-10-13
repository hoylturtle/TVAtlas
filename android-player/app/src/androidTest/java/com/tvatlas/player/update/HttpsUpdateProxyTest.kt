package com.tvatlas.player.update

import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.core.subscription.ClashSubscription
import com.tvatlas.player.debug.DebugLog
import com.tvatlas.player.proxy.MihomoRuntime
import com.tvatlas.player.proxy.ProxyClientPool
import com.tvatlas.player.storage.CredentialVault
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class HttpsUpdateProxyTest {
    @Test fun realEmbeddedSocksPreservesHttpsHostAndCanRecoverUpdateAndApkFrom400() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val certificate = HeldCertificate.Builder().commonName("fixture")
            .addSubjectAlternativeName("api.github.com").addSubjectAlternativeName("github.com").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val origin = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val proxy = ServerSocket(0)
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val targets = ConcurrentLinkedQueue<String>()
        val workers = Executors.newCachedThreadPool { task -> Thread(task, "fixture-tunnel").apply { isDaemon = true } }
        workers.execute {
            while (!proxy.isClosed) {
                val incoming = try { proxy.accept() } catch (_: Exception) { break }
                sockets.add(incoming)
                workers.execute {
                    try {
                        incoming.soTimeout = 15000
                        val input = incoming.getInputStream()
                        val header = ByteArrayOutputStream()
                        while (header.size() < 8192) {
                            val byte = input.read(); if (byte < 0) error("Incomplete CONNECT")
                            header.write(byte)
                            if (header.toString("US-ASCII").endsWith("\r\n\r\n")) break
                        }
                        val destination = header.toString("US-ASCII").lineSequence().first().split(" ")[1]
                        require(destination in listOf("api.github.com:${origin.port}", "github.com:${origin.port}"))
                        targets.add(destination)
                        val remote = Socket("127.0.0.1", origin.port).also { sockets.add(it); it.soTimeout = 15000 }
                        incoming.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        incoming.getOutputStream().flush()
                        workers.execute { runCatching { input.copyTo(remote.getOutputStream()) } }
                        remote.getInputStream().copyTo(incoming.getOutputStream())
                    } catch (_: Exception) { }
                    finally { incoming.close() }
                }
            }
        }
        val vault = CredentialVault(context)
        val core = MihomoRuntime(context, vault)
        val trafficFile = File(context.cacheDir, "https-traffic-fixture.json").apply { delete() }
        val traffic = com.tvatlas.player.traffic.TrafficStore(trafficFile)
        val pool = ProxyClientPool(vault, core, traffic)
        val parsed = ClashSubscription.parse("proxies:\n  - {name: HTTPS fixture, type: http, server: 127.0.0.1, port: ${proxy.localPort}}", "https-update-fixture")
        val node = parsed.nodes.single()
        val logFile = File(context.cacheDir, "https-update-fixture.log").apply { delete() }
        val log = DebugLog(logFile, "fixture")
        val destination = File(context.cacheDir, "fixture-update.apk")
        val api = "https://api.github.com:${origin.port}/repos/hoylturtle/TVAtlas"
        val web = "https://github.com:${origin.port}/hoylturtle/TVAtlas"
        val body = "verified-apk-fixture"
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val commit = "a".repeat(40)
        val prefix = "$web/releases/download/v0.1.29-beta.1/"
        val listing = """[{"draft":false,"tag_name":"v0.1.29-beta.1","target_commitish":"$commit","assets":[
            {"id":101,"name":"release-info.json","state":"uploaded","size":500,"browser_download_url":"${prefix}release-info.json"},
            {"id":202,"name":"TVAtlas-Player-v0.1.29-release.apk","state":"uploaded","size":${body.length},"browser_download_url":"${prefix}TVAtlas-Player-v0.1.29-release.apk"}]}]"""
        val manifest = """{"versionCode":30,"versionName":"0.1.29","minSdk":23,"releaseTag":"v0.1.29-beta.1",
            "commit":"$commit","signingCertificateSha256":"$RELEASE_CERTIFICATE_SHA256","apkSize":${body.length},"apkSha256":"$hash"}"""
        try {
            vault.putSecret("node:${node.profile.id}", node.config.toString())
            core.update(listOf(node.profile)); pool.update(listOf(node.profile))
            origin.enqueue(MockResponse().setResponseCode(400))
            origin.enqueue(MockResponse().setBody(listing))
            origin.enqueue(MockResponse().setResponseCode(400))
            origin.enqueue(MockResponse().setBody(manifest))
            origin.enqueue(MockResponse().setResponseCode(400))
            origin.enqueue(MockResponse().setBody(body))
            val protocols = mutableListOf<List<Protocol>>()
            val target = RouteTarget.proxy(node.profile.id)
            val release = withUpdateRoutes(listOf(target), false, client = {
                pool.client(it).newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            }, log = log) { client ->
                protocols += client.protocols
                UpdateRepository(client, api, web, log = log).latestAsync()
            }
            assertEquals(30, release.versionCode)
            assertEquals(2, protocols.size)
            assertEquals(listOf(Protocol.HTTP_1_1), protocols.last())
            val downloadClient = pool.client(target).newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .followRedirects(true).build()
            ApkDownloader(downloadClient, log, "$api/releases/assets/").download(release, destination)
            assertEquals(body, destination.readText())
            traffic.flush()
            val usage = kotlinx.serialization.json.Json.decodeFromString<com.tvatlas.player.traffic.TrafficSnapshot>(trafficFile.readText())
            assertEquals(0L, usage.direct)
            assertTrue(usage.proxy >= body.toByteArray().size.toLong())
            assertEquals("PROXY:${node.profile.id}", usage.entries.single().id)
            assertEquals(6, origin.requestCount)
            assertTrue(targets.contains("api.github.com:${origin.port}"))
            assertTrue(targets.contains("github.com:${origin.port}"))
            val paths = (1..6).map { origin.takeRequest() }
            assertEquals("/repos/hoylturtle/TVAtlas/releases?per_page=10", paths[0].path)
            assertEquals(paths[0].path, paths[1].path)
            assertEquals("/repos/hoylturtle/TVAtlas/releases/assets/101", paths[3].path)
            assertEquals("/repos/hoylturtle/TVAtlas/releases/assets/202", paths[5].path)
            assertTrue(paths.all { it.getHeader("Authorization") == null })
            val snapshot = log.snapshot()
            assertTrue(snapshot.contains("stage=release-list"))
            assertTrue(snapshot.contains("status=400"))
            assertTrue(snapshot.contains("protocol=h2"))
            assertTrue(snapshot.contains("UPDATE_COMPAT"))
            assertTrue(snapshot.contains("UPDATE_DOWNLOAD_HTTP"))
            assertFalse(snapshot.contains("https://"))
        } finally {
            destination.delete(); File(destination.path + ".part").delete()
            pool.close(); core.close(); vault.putSecret("node:${node.profile.id}", null)
            proxy.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
            origin.shutdown(); log.close(); logFile.delete()
            traffic.shutdown(); trafficFile.delete()
        }
    }
}
