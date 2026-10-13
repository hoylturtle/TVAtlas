package com.tvatlas.player.update
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
class ApkDownloaderTest {
    private fun release(url: String, body: String) = PublishedUpdate(14, "0.1.13", 23, "", url,
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }, body.toByteArray().size.toLong())
    @Test fun verifiedDownloadIsPromotedOnlyAfterHashAndSizeMatch() = runBlocking {
        MockWebServer().use { server ->
            val folder = Files.createTempDirectory("update").toFile()
            try {
                val file = folder.resolve("app.apk")
                server.enqueue(MockResponse().setBody("signed-apk"))
                var progress = 0
                ApkDownloader(OkHttpClient()).download(release(server.url("/app.apk").toString(), "signed-apk"), file) { progress = it }
                assertEquals("signed-apk", file.readText()); assertEquals(100, progress)
                assertFalse(folder.resolve("app.apk.part").exists())
            } finally { folder.deleteRecursively() }
        }
    }
    @Test fun tamperedDownloadCannotReplacePreviouslyVerifiedFile() = runBlocking {
        MockWebServer().use { server ->
            val folder = Files.createTempDirectory("update").toFile()
            try {
                val file = folder.resolve("app.apk").apply { writeText("previous") }
                server.enqueue(MockResponse().setBody("tamper-apk"))
                try { ApkDownloader(OkHttpClient()).download(release(server.url("/app.apk").toString(), "signed-apk"), file) {}; fail("Hash mismatch accepted") }
                catch (_: UpdateException) { }
                assertEquals("previous", file.readText()); assertFalse(folder.resolve("app.apk.part").exists())
            } finally { folder.deleteRecursively() }
        }
    }
    @Test fun browser400UsesOfficialAssetAlternativeAndStillVerifiesBytes() = runBlocking {
        MockWebServer().use { server ->
            val folder = Files.createTempDirectory("update").toFile()
            try {
                server.enqueue(MockResponse().setResponseCode(400))
                server.enqueue(MockResponse().setBody("signed-apk"))
                val prefix = server.url("/api/releases/assets/").toString()
                val info = release(server.url("/app.apk").toString(), "signed-apk").copy(assetApiUrl = prefix + "202")
                val file = folder.resolve("app.apk")
                ApkDownloader(OkHttpClient(), assetApiPrefix = prefix).download(info, file)
                assertEquals("signed-apk", file.readText())
                server.takeRequest()
                assertEquals("/api/releases/assets/202", server.takeRequest().path)
                assertFalse(folder.resolve("app.apk.part").exists())
            } finally { folder.deleteRecursively() }
        }
    }

}
