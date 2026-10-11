package com.tvatlas.player.source

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MyTvSuperSourceTest {
    private val session = """{"supported_country":true,"user":{"token":"fixture-token","guest_mode":true}}"""
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
    @Test fun missingCredentialsAreDistinctFromRejectedRegion() {
        val missing = """{"supported_country":true,"country_code":"HK"}"""
        assertThrows(MyTvSuperSource.MissingGuestCredentials::class.java) { MyTvSuperSource.parseSession(missing) }
        val summary = MyTvSuperSource.sessionSummary(session)
        assertTrue(summary.contains("tokenInUser=true"))
        assertFalse(summary.contains("fixture-token"))
    }
    @Test fun guestInitializationRetriesSessionOnceBeforeCheckout() = runBlocking {
        val paths = mutableListOf<String>()
        var sessions = 0
        var pairings = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths.add(request.url.encodedPath)
            if (request.url.encodedPath == "/api/auth/pairDevice/") {
                pairings++
                assertEquals("POST", request.method)
                assertNull(request.header("Authorization"))
                assertEquals("https://www.mytvsuper.com", request.header("Origin"))
                val buffer = okio.Buffer()
                request.body!!.writeTo(buffer)
                val body = kotlinx.serialization.json.Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertEquals(setOf("device_id", "lang", "incognito"), body.keys)
                assertTrue(body["device_id"]!!.jsonPrimitive.content.matches(Regex("[0-9]{26}")))
                assertEquals("tc", body["lang"]!!.jsonPrimitive.content)
                assertEquals(false, body["incognito"]!!.jsonPrimitive.boolean)
            }
            val body = when {
                request.url.encodedPath.contains("getSession") -> {
                    sessions++
                    if (sessions == 1) """{"supported_country":true,"country_code":"HK"}""" else session
                }
                request.url.host == "www.mytvsuper.com" -> """{"success":true}"""
                else -> checkout()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        assertEquals("fixture-token", MyTvSuperSource.resolve(client).userToken)
        assertEquals(1, pairings)
        assertEquals(2, sessions)
        assertEquals(listOf("/api/auth/getSession/self/", "/api/auth/pairDevice/", "/api/auth/getSession/self/", "/v1/channel/checkout"), paths)
    }
    @Test fun missingCredentialsAfterBootstrapFailWithoutLoopingOrCheckout() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.url.encodedPath.contains("getSession")) """{"supported_country":true}""" else """{"success":true}""").toResponseBody()).build()
        }.build()
        try { MyTvSuperSource.resolve(client); fail("Expected missing credentials") }
        catch (e: IOException) { assertTrue(e.message!!.contains("配对后仍未返回")) }
        assertEquals(3, requests)
    }

    @Test fun rejectedRegionNeverCreatesGuestDevice() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"supported_country":false}""".toResponseBody()).build()
        }.build()
        try { MyTvSuperSource.resolve(client); fail("Expected region failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("当前地区")) }
        assertEquals(1, requests)
    }
    @Test fun failedPairingNeverRequestsCheckoutOrRetriesIndefinitely() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            paths.add(req.url.encodedPath)
            val body = if (paths.size == 1) """{"supported_country":true}"""
                else """{"errors":{"code":"fixture-private-error"}}"""
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        try { MyTvSuperSource.resolve(client); fail("Expected pairing failure") }
        catch (e: IOException) { assertTrue(e.message!!.contains("未接受访客设备配对")); assertFalse(e.message!!.contains("fixture-private-error")) }
        assertEquals(listOf("/api/auth/getSession/self/", "/api/auth/pairDevice/"), paths)
    }
    @Test fun emptyRootTokenDoesNotHideNestedGuestToken() {
        for (value in listOf("null", "\"\""))
            assertEquals("fixture-token", MyTvSuperSource.parseSession(session.replaceFirst("{", "{\"token\":$value,")))
    }

}
