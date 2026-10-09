package com.tvatlas.player.source

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MyTvSuperSourceTest {
    private val session = """{"supported_country":true,"token":"fixture-token","user":{"guest_mode":true}}"""
    private fun checkout(host: String = "edgeware-live.edgeware.tvb.com", stage: String = "free") = """
        {"content_id":"ott_J_h264","protocol":"dash","drm":"cenc_m","video_stage":"$stage",
         "profiles":[{"quality":"high","streaming_path":"https://$host/high/index.mpd?sig=fixture"},
                     {"quality":"auto","streaming_path":"https://$host/auto/index.mpd?sig=fixture"}]}
    """.trimIndent()
    @Test fun recognizesOnlyOfficialJadePage() {
        assertTrue(MyTvSuperSource.recognizes(MyTvSuperSource.PAGE))
        assertTrue(MyTvSuperSource.recognizes(MyTvSuperSource.PAGE + "%E7%BF%A1%E7%BF%A0%E5%8F%B0/"))
        for (value in listOf("http://www.mytvsuper.com/tc/live/81/", "https://evil.test/tc/live/81/",
            "https://www.mytvsuper.com/tc/live/82/", MyTvSuperSource.PAGE + "?token=secret"))
            assertFalse(MyTvSuperSource.recognizes(value))
    }
    @Test fun rejectedRegionCannotUseToken() {
        assertThrows(IOException::class.java) { MyTvSuperSource.parseSession(session.replace("true", "false")) }
    }
    @Test fun choosesAutomaticQualityAndRejectsUnexpectedHostsAndPaidContent() {
        assertTrue(MyTvSuperSource.parseCheckout(checkout()).contains("/auto/index.mpd"))
        assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout("evil.test")) }
        assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout(stage = "paid")) }
    }
    @Test fun resolvesFreshGuestAndAuthenticatesOnlyCheckout() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.url.host == "www.mytvsuper.com") session else checkout())
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = MyTvSuperSource.resolve(client)
        assertEquals("fixture-token", result.userToken)
        assertNull(requests[0].header("Authorization"))
        assertEquals("Bearer fixture-token", requests[1].header("Authorization"))
        assertEquals("com.tvb.mytvsuper.web", requests[1].header("App-Domain"))
        assertTrue(result.url.contains("/auto/index.mpd"))
    }
    @Test fun redirectedLicenseCannotSendUserTokenToAnotherHost() {
        val client = MyTvSuperSource.licenseClient(OkHttpClient())
        val request = Request.Builder().url("https://evil.test/license")
            .header("X-User-Token", "fixture-token").build()
        assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
    }
    @Test fun httpErrorDoesNotExposeServerPayloadOrToken() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("secret-token".toResponseBody()).build()
        }.build()
        try { MyTvSuperSource.resolve(client); fail("Expected failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("403")); assertFalse(e.message!!.contains("secret-token")) }
    }
}
