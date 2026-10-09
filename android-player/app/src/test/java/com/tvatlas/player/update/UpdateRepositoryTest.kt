package com.tvatlas.player.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryTest {
    private val sha = "a".repeat(40)
    private fun run(id: Int, conclusion: String = "success") = """{"id":$id,"status":"completed","conclusion":"$conclusion","event":"push","head_branch":"main","head_sha":"$sha"}"""
    private val info = """{"versionCode":5,"versionName":"0.1.4","minSdk":23,"notes":"修复播放","downloadUrl":"https://untrusted.invalid/app.apk"}"""
    private fun artifact(expired: Boolean = false) = """{"artifacts":[{"id":99,"name":"TVAtlas-Player-v0.1.4-debug","expired":$expired}]}"""
    private fun repository(server: MockWebServer) = UpdateRepository(OkHttpClient(), server.url("/api").toString(), server.url("/raw").toString(), "https://github.com/hoylturtle/TVAtlas")

    @Test fun skipsFailedBuildAndPinsMetadataAndDownloadToVerifiedCommitAndArtifact() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"workflow_runs":[${run(1, "failure")},${run(2)}]}"""))
            server.enqueue(MockResponse().setBody(info)); server.enqueue(MockResponse().setBody(artifact()))
            val release = repository(server).latest()
            assertEquals(5, release.versionCode)
            assertEquals("https://github.com/hoylturtle/TVAtlas/actions/runs/2/artifacts/99", release.downloadUrl)
            server.takeRequest()
            assertEquals("/raw/$sha/android-player/release-info.json", server.takeRequest().path)
            assertEquals("/api/actions/runs/2/artifacts?per_page=100", server.takeRequest().path)
        }
    }
    @Test fun expiredArtifactIsNotOfferedAndPreviousDownloadableBuildIsUsed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"workflow_runs":[${run(2)},${run(1)}]}"""))
            server.enqueue(MockResponse().setBody(info)); server.enqueue(MockResponse().setBody(artifact(true)))
            server.enqueue(MockResponse().setBody(info)); server.enqueue(MockResponse().setBody(artifact()))
            assertTrue(repository(server).latest().downloadUrl.contains("/runs/1/"))
        }
    }
    @Test fun serviceLimitProvidesRetryMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            try { repository(server).latest(); fail("Expected retryable service error") }
            catch (error: UpdateException) { assertTrue(error.message!!.contains("稍后重试")) }
        }
    }
    @Test fun oldBuildWithoutVersionMetadataCannotBeOffered() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"workflow_runs":[${run(1)}]}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            try { repository(server).latest(); fail("Expected unavailable version") }
            catch (error: UpdateException) { assertTrue(error.message!!.contains("可下载")) }
        }
    }
}
