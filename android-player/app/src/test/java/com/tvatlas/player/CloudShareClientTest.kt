package com.tvatlas.player

import com.tvatlas.player.cloud.CloudShareClient
import com.tvatlas.core.cloud.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CloudShareClientTest {
    @Test fun parses115FoldersFilesAndPaginationWithoutRequiringSecretsInFixtures() {
        val d = CloudShareClient.parse115(Json.parseToJsonElement("""{"state":true,"data":{"count":3,"list":[{"cid":"10","n":"电影"},{"fid":"11","n":"E01.mp4","s":"1024"}]}}""").jsonObject(), 0)
        assertEquals(2, d.files.size); assertTrue(d.files[0].directory); assertTrue(d.files[1].video); assertEquals(2, d.nextOffset)
    }
    @Test fun doesNotCallAnErrorOrMalformedResponseAnEmptyFolder() {
        assertThrows(CloudException::class.java) { CloudShareClient.parse115(Json.parseToJsonElement("""{"state":false,"errno":401}""").jsonObject(), 0) }
        assertThrows(CloudException::class.java) { CloudShareClient.parseQuark(Json.parseToJsonElement("""{"code":0,"data":{}}""").jsonObject(), 0) }
    }
    @Test fun readOnly115RequestIsEncodedAndUsesNoAccountCredentials() = runBlocking {
        var seen: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"state":true,"data":{"count":0,"list":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = CloudShareClient(client).list(CloudShare(CloudProvider.PAN115, "sExampleShare", "aB12"))
        assertTrue(result.files.isEmpty()); assertEquals("GET", seen!!.method)
        assertEquals("webapi.115.com", seen!!.url.host); assertEquals("aB12", seen!!.url.queryParameter("receive_code"))
        assertNull(seen!!.header("Authorization")); assertNull(seen!!.header("Cookie"))
        assertFalse(seen!!.url.encodedPath.contains("downurl"))
    }
    @Test fun baiduIsExplicitlyUnsupportedRatherThanFakingPlayable() {
        val error = assertThrows(CloudException::class.java) { runBlocking { CloudShareClient(OkHttpClient()).list(CloudShare(CloudProvider.BAIDU, "1ExampleShare")) } }
        assertEquals(CloudFailure.UNSUPPORTED, error.kind)
    }
    private fun kotlinx.serialization.json.JsonElement.jsonObject() = this as kotlinx.serialization.json.JsonObject
}
