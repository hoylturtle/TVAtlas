package com.tvatlas.player.source

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ViuTvSourceTest {
    private val fixture = """{"responseCode":"SUCCESS","asset":["https://viu-live.akamaized.net/live/index.mpd"],"drmToken":"fixture-token"}"""
    @Test fun usesOfficialPageAndRejectsRegionAndUnexpectedMediaHost() {
        assertTrue(ViuTvSource.recognizes(ViuTvSource.PAGE))
        assertFalse(ViuTvSource.recognizes(ViuTvSource.PAGE + "?token=secret"))
        val source = ViuTvSource.parseCheckout(fixture)
        assertTrue(source.drmRequired)
        assertEquals(ViuTvSource.LICENSE, source.licenseUrl)
        assertThrows(IOException::class.java) { ViuTvSource.parseCheckout("""{"responseCode":"GEO_CHECK_FAIL"}""") }
        assertThrows(IOException::class.java) { ViuTvSource.parseCheckout(fixture.replace("viu-live.akamaized.net", "evil.test")) }
        assertFalse(ViuTvSource.parseCheckout(fixture.replace(",\"drmToken\":\"fixture-token\"", "").replace("index.mpd", "index.m3u8")).drmRequired)
    }
    @Test fun viuTvSixUses96CheckoutAndSameRegionGroup() = runBlocking {
        var sent = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            sent = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            assertEquals(ViuTvSource.SIX_PAGE, chain.request().header("Referer"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(fixture.toResponseBody()).build()
        }.build()
        assertTrue(ViuTvSource.recognizes(ViuTvSource.SIX_PAGE))
        ViuTvSource.resolve(client, page = ViuTvSource.SIX_PAGE)
        val json = Json.parseToJsonElement(sent).jsonObject
        assertEquals("096", json["channelno"]!!.jsonPrimitive.content)
        assertEquals("096", json["contentId"]!!.jsonPrimitive.content)
        assertTrue(ViuTvSource.playlist(ViuTvSource.SIX_PAGE).contains("香港 · 综合"))
    }
    @Test fun checkoutAndLicenseFollowIndependentViuProtocol() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            val body = if (chain.request().url.toString() == ViuTvSource.API) fixture else "binary-license"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val source = ViuTvSource.resolve(client)
        val sent = okio.Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
        val json = Json.parseToJsonElement(sent).jsonObject
        assertEquals("099", json["channelno"]!!.jsonPrimitive.content)
        assertEquals("ANDRIOD_WEB", json["deviceType"]!!.jsonPrimitive.content)
        assertFalse(requests.single().headers.names().contains("X-User-Token"))
        // Put the capture interceptor after the wrapping interceptor to observe the actual outgoing envelope.
        var envelope = ""
        val license = ViuTvSource.licenseClient(OkHttpClient(), source.userToken).newBuilder().addInterceptor { chain ->
            envelope = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("binary".toResponseBody()).build()
        }.build()
        license.newCall(Request.Builder().url(ViuTvSource.LICENSE).post(byteArrayOf(1, 2, 3).toRequestBody("application/octet-stream".toMediaType())).build()).execute().close()
        val wrapped = Json.parseToJsonElement(envelope).jsonObject
        assertEquals("AQID", wrapped["rawLicenseRequestBase64"]!!.jsonPrimitive.content)
        assertEquals("fixture-token", wrapped["drmToken"]!!.jsonPrimitive.content)
    }
}
