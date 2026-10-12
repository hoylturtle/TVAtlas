package com.tvatlas.player.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryTest {
    private val sha = "a".repeat(40)
    private val certificate = "7de8ec4d38a6c7854981b73fe34161c17d97fd93555358bd11f46fecde4ab878"
    private fun manifest(version: String = "0.1.13", code: Int = 14, cert: String = certificate) = """
        {"versionCode":$code,"versionName":"$version","minSdk":23,"notes":"测试版","coreVersion":"v1.19.32",
         "releaseTag":"v$version-beta.1","commit":"$sha","signingCertificateSha256":"$cert","apkSize":1000,"apkSha256":"${"b".repeat(64)}"}
    """.trimIndent()
    private fun release(server: MockWebServer, version: String = "0.1.13", draft: Boolean = false,
        apkName: String = "TVAtlas-Player-v$version-release.apk", foreignUrl: Boolean = false): String {
        val prefix = server.url("/web/releases/download/v$version-beta.1/").toString()
        val apkUrl = if (foreignUrl) "https://untrusted.invalid/app.apk" else prefix + apkName
        return """{"tag_name":"v$version-beta.1","draft":$draft,"prerelease":true,"target_commitish":"$sha",
            "assets":[{"name":"release-info.json","state":"uploaded","size":500,"browser_download_url":"${prefix}release-info.json"},
            {"name":"$apkName","state":"uploaded","size":1000,"browser_download_url":"$apkUrl"}]}"""
    }
    private fun repository(server: MockWebServer) = UpdateRepository(OkHttpClient(),
        server.url("/api").toString(), server.url("/web").toString())

    @Test fun publishedPrereleaseReturnsDirectPublicApkWithoutActionsOrRawRequests() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release(server)}]"))
            server.enqueue(MockResponse().setBody(manifest()))
            val result = repository(server).latest()
            assertEquals(14, result.versionCode)
            assertEquals("v1.19.32", result.coreVersion)
            assertEquals(server.url("/web/releases/download/v0.1.13-beta.1/TVAtlas-Player-v0.1.13-release.apk").toString(), result.downloadUrl)
            assertEquals("/api/releases?per_page=10", server.takeRequest().path)
            assertEquals("/web/releases/download/v0.1.13-beta.1/release-info.json", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun draftsAreSkippedAndHighestVersionCodeWinsRegardlessOfReleaseOrder() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release(server, draft = true)},${release(server, "0.1.12")},${release(server)}]"))
            server.enqueue(MockResponse().setBody(manifest("0.1.12", 13)))
            server.enqueue(MockResponse().setBody(manifest()))
            assertEquals(14, repository(server).latest().versionCode)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun missingManifestDoesNotOfferAnUnverifiedApk() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release(server)}]"))
            server.enqueue(MockResponse().setResponseCode(404))
            try { repository(server).latest(); fail("Expected unavailable release") }
            catch (error: UpdateException) { assertTrue(error.message!!.contains("公开")) }
        }
    }
    @Test fun certificateAndTagAndCommitMustMatchPublishedRelease() {
        val invalid = listOf(manifest(cert = "c".repeat(64)), manifest().replace("v0.1.13-beta.1", "v0.1.12-beta.1"),
            manifest().replace(sha, "d".repeat(40)), manifest().replace("b".repeat(64), "invalid"), manifest().replace("1000", "1001"))
        for (info in invalid) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release(server)}]"))
            server.enqueue(MockResponse().setBody(info))
            try { repository(server).latest(); fail("Expected incompatible release") }
            catch (_: UpdateException) { assertEquals(2, server.requestCount) }
        }
    }
    @Test fun foreignDownloadLinksAndTemporaryArtifactsCannotBeOffered() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release(server, foreignUrl = true)}]"))
            server.enqueue(MockResponse().setBody(manifest()))
            try { repository(server).latest(); fail("Expected rejected external asset") }
            catch (_: UpdateException) { }
        }
        for (name in listOf("TVAtlas-Player-v0.1.13-debug.apk", "TVAtlas-v0.1.13-unsigned.apk", "build.zip"))
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("[${release(server, apkName = name)}]"))
                server.enqueue(MockResponse().setBody(manifest()))
                try { repository(server).latest(); fail("Expected rejected temporary artifact") }
                catch (_: UpdateException) { }
            }
    }
    @Test fun serviceLimitProvidesRetryMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            try { repository(server).latest(); fail("Expected retryable service error") }
            catch (error: UpdateException) { assertTrue(error.message!!.contains("稍后重试")) }
        }
    }
    @Test fun laterAssetOutageDoesNotDiscardAlreadyVerifiedUpdateOrClaimCompleteCheck() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release(server)},${release(server, "0.1.12")}]"))
            server.enqueue(MockResponse().setBody(manifest()))
            server.enqueue(MockResponse().setResponseCode(503))
            val result = repository(server).latest()
            assertEquals(14, result.versionCode)
            assertFalse(result.checkComplete)
            assertTrue(result.apkSha256.matches(Regex("[a-f0-9]{64}")))
        }
    }

}
