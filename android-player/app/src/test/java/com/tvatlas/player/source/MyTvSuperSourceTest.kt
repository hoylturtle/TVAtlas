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
    @Test fun webGuestLicenseUsesAndroidWebEntitlementAndBinaryPayloadType() {
        val headers = MyTvSuperSource.licenseHeaders("fixture-token")
        assertEquals("web_android", headers["X-Client-Platform"])
        assertEquals("application/octet-stream", headers["Content-Type"])
        assertEquals("fixture-token", headers["X-User-Token"])
        assertEquals("super", headers["X-Service-Id"])
        assertFalse(headers.containsKey("Cookie"))
    }
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
            "https://www.mytvsuper.com/tc/live/85/", MyTvSuperSource.PAGE + "?token=secret"))
            assertFalse(MyTvSuperSource.recognizes(value))
    }
    @Test fun officialPearlAndPlusUseTheirOwnCheckoutAndLicense() = runBlocking {
        for (entry in MyTvSuperSource.channels.filter { it.network != "J" }) {
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests.add(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (chain.request().url.host == "www.mytvsuper.com") session else
                        checkout().replace("ott_J_h264", "ott_${entry.network}_h264")).toResponseBody()).build()
            }.build()
            val result = MyTvSuperSource.resolve(client, page = entry.page)
            assertEquals(entry.network, requests.last().url.queryParameter("network_code"))
            assertTrue(result.licenseUrl.endsWith("ott_${entry.network}_h264"))
            assertTrue(MyTvSuperSource.playlist(entry.page).contains(entry.name))
            assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout(), entry.network) }
        }
    }
    @Test fun freeCatalogHasTypedGroupsAndNewsNetworkFromOfficialDirectory() {
        val channels = com.tvatlas.core.playlist.M3uParser.parse(MyTvSuperSource.playlist(MyTvSuperSource.FREE_PAGE), "official")
        assertEquals(31, channels.size)
        assertFalse(channels.any { it.group == "香港" })
        assertEquals("香港 · 新闻", channels.first { it.name == "无线新闻台" }.group)
        assertEquals("C", MyTvSuperSource.channel("https://www.mytvsuper.com/tc/live/83/")!!.network)
        assertTrue(channels.any { it.group == "香港 · 影视" })
        assertEquals(setOf("香港 · 综合", "香港 · 新闻", "香港 · 影视"), channels.map { it.group }.toSet())
        assertNull(MyTvSuperSource.channel("https://www.mytvsuper.com/tc/live/85/"))
    }
    @Test fun rejectedRegionCannotUseToken() {
        assertThrows(IOException::class.java) { MyTvSuperSource.parseSession(session.replace("true", "false")) }
    }
    @Test fun choosesAutomaticQualityAndRejectsUnexpectedHostsAndPaidContent() {
        assertTrue(MyTvSuperSource.parseCheckout(checkout()).contains("/auto/index.mpd"))
        assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout("evil.test")) }
        assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout(stage = "paid")) }
    }
    @Test fun acceptsOfficialSessionCdnButRejectsLookalikeHosts() {
        assertTrue(MyTvSuperSource.parseCheckout(checkout("hk4-edge18-1.edgeware.tvb.com")).contains("hk4-edge18-1"))
        listOf("edgeware.tvb.com.evil.test", "evil-edgeware.tvb.com", "evil.test").forEach { host ->
            assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout(host)) }
        }
        assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout().replace("https://", "http://")) }
        assertThrows(IOException::class.java) { MyTvSuperSource.parseCheckout(checkout("edgeware-live.edgeware.tvb.com:8443")) }
    }
    @Test fun rejectedConfigurationIsNotClassifiedAsAnUnreachableNode() {
        val error = assertThrows(SourceResolutionException::class.java) { MyTvSuperSource.parseCheckout(checkout(stage = "paid")) }
        assertEquals(com.tvatlas.core.player.FailureKind.MANIFEST, error.failure.kind)
        val geo = assertThrows(SourceResolutionException::class.java) { MyTvSuperSource.parseSession(session.replace("true", "false")) }
        assertEquals(403, geo.failure.httpStatus)
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
