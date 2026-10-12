package com.tvatlas.player.source

import okio.ByteString.Companion.toByteString
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Public ViuTV web checkout and its documented Widevine request envelope; no saved credentials. */
object ViuTvSource {
    const val PAGE = "https://viu.tv/ch/99"
    const val API = "https://api.viu.now.com/p8/3/getLiveURL"
    const val LICENSE = "https://fwp.nowe.com/wrapperWV"
    const val PLAYLIST = "#EXTM3U\n#EXTINF:-1 tvg-id=\"ViuTV.hk\" group-title=\"香港 · 综合\",ViuTV\n$PAGE\n"
    const val SIX_PAGE = "https://viu.tv/ch/96"
    fun recognizes(value: String): Boolean = value.trimEnd('/') in listOf(PAGE, SIX_PAGE)
    fun playlist(value: String): String {
        require(recognizes(value))
        return if (value.trimEnd('/') == SIX_PAGE) "#EXTM3U\n#EXTINF:-1 tvg-id=\"ViuTVsix.hk\" group-title=\"香港 · 综合\",ViuTVsix\n$SIX_PAGE\n" else PLAYLIST
    }
    fun parseCheckout(text: String): MyTvSuperSource.Playback {
        val root = Json.parseToJsonElement(text).jsonObject
        if ((root["responseCode"] as? JsonPrimitive)?.contentOrNull == "GEO_CHECK_FAIL")
            throw IOException("ViuTV 官方服务未接受当前地区，请检查所选节点")
        val url = ((root["asset"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()
            ?: throw IOException("ViuTV 官方未返回播放地址")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            !(url.host.endsWith(".now.com") || url.host.endsWith(".viu.tv") || url.host.endsWith(".nowe.com") || url.host.endsWith(".akamaized.net")))
            throw IOException("ViuTV 官方播放地址校验失败")
        val token = (root["drmToken"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (token.length > 16384 || token.any { it == '\r' || it == '\n' }) throw IOException("ViuTV 授权凭据无效")
        return MyTvSuperSource.Playback(url.toString(), token, LICENSE,
            drmRequired = token.isNotBlank(), mimeType = if (token.isNotBlank()) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
    }
    suspend fun resolve(base: OkHttpClient, log: com.tvatlas.player.debug.DebugLog? = null, page: String = PAGE): MyTvSuperSource.Playback = withContext(Dispatchers.IO) {
        require(recognizes(page)) { "不支持的 ViuTV 官方频道" }
        val channelNumber = if (page.trimEnd('/') == SIX_PAGE) "096" else "099"
        val uid = UUID.randomUUID().toString().replace("-", "").take(18)
        val body = buildJsonObject {
            put("callerReferenceNo", SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()))
            put("channelno", channelNumber); put("contentId", channelNumber); put("contentType", "Channel")
            put("mode", "prod"); put("PIN", "password"); put("cookie", uid); put("deviceId", uid)
            put("deviceType", "ANDRIOD_WEB"); put("format", "HLS")
        }.toString().toRequestBody("text/plain".toMediaType())
        val client = base.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
        log?.event("INFO", "VIUTV_CHECKOUT", "start host=api.viu.now.com")
        client.newCall(Request.Builder().url(API).header("Origin", "https://viu.tv").header("Referer", page).post(body).build()).execute().use {
            log?.event("INFO", "VIUTV_CHECKOUT", "status=${it.code}")
            if (!it.isSuccessful) throw IOException("ViuTV 播放配置请求失败（HTTP ${it.code}）")
            val input = it.body?.byteStream() ?: throw IOException("ViuTV 返回空响应")
            val bytes = input.readBytesLimited()
            parseCheckout(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer); if (n < 0) break
            if (out.size() + n > 1024 * 1024) throw IOException("ViuTV 播放配置过大")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    fun licenseClient(base: OkHttpClient, token: String, log: com.tvatlas.player.debug.DebugLog? = null): OkHttpClient =
        base.newBuilder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            if (request.url.toString() != LICENSE) return@addInterceptor chain.proceed(request)
            val payload = okio.Buffer().also { request.body?.writeTo(it) }.readByteArray()
            val body = buildJsonObject {
                put("rawLicenseRequestBase64", payload.toByteString().base64()); put("drmToken", token)
            }.toString().toRequestBody("application/json".toMediaType())
            val response = chain.proceed(request.newBuilder().header("Origin", "https://viu.tv").header("Referer", PAGE)
                .removeHeader("Content-Length").header("Content-Type", "application/json").post(body).build())
            log?.event("INFO", "VIUTV_LICENSE", "status=${response.code}")
            response
        }.build()
}
