package com.tvatlas.player.source

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Official free TVB channels. Never persist session credentials or signed stream URLs. */
object MyTvSuperSource {
    const val PAGE = "https://www.mytvsuper.com/tc/live/81/"
    const val PLAYLIST = "#EXTM3U\n#EXTINF:-1 tvg-id=\"Jade.hk\" group-title=\"香港\",翡翠台\n$PAGE\n"
    const val PEARL_PAGE = "https://www.mytvsuper.com/tc/live/84/"
    const val PLUS_PAGE = "https://www.mytvsuper.com/tc/live/82/"
    data class OfficialChannel(val page: String, val network: String, val name: String, val tvgId: String)
    val channels = listOf(OfficialChannel(PAGE, "J", "翡翠台", "Jade.hk"),
        OfficialChannel(PEARL_PAGE, "P", "明珠台", "Pearl.hk"),
        OfficialChannel(PLUS_PAGE, "B", "TVB Plus", "TVBPlus.hk"))
    fun channel(value: String): OfficialChannel? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host != "www.mytvsuper.com" || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return channels.firstOrNull { entry ->
            val path = entry.page.toHttpUrlOrNull()!!.encodedPath.trimEnd('/')
            url.encodedPath.trimEnd('/') == path || (entry.network == "J" &&
                url.encodedPath.trimEnd('/') == "$path/%E7%BF%A1%E7%BF%A0%E5%8F%B0")
        }
    }
    fun playlist(value: String): String {
        val entry = requireNotNull(channel(value))
        return "#EXTM3U\n#EXTINF:-1 tvg-id=\"${entry.tvgId}\" group-title=\"香港\",${entry.name}\n${entry.page}\n"
    }
    const val LICENSE = "https://wv.drm.tvb.com/wvproxy/mlicense?contentid=ott_J_h264"
    fun licenseHeaders(userToken: String) = mapOf(
        "X-User-Token" to userToken, "X-Client-Platform" to "web_android",
        "X-Service-Id" to "super", "Content-Type" to "application/octet-stream",
        "Origin" to "https://www.mytvsuper.com", "Referer" to PAGE,
    )
    fun recognizes(value: String): Boolean = channel(value) != null
    data class Playback(val url: String, val userToken: String, val licenseUrl: String = LICENSE)
    class MissingGuestCredentials : IOException("官方访客会话未返回有效凭据，需要完成访客初始化")
    fun sessionSummary(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        val country = root["country_code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "unknown"
        val rootToken = root["token"] as? JsonPrimitive
        val userToken = (root["user"] as? JsonObject)?.get("token") as? JsonPrimitive
        return "supported_country=${(root["supported_country"] as? JsonPrimitive)?.booleanOrNull} country=$country " +
            "tokenAtRoot=${!rootToken?.contentOrNull.isNullOrBlank()} tokenInUser=${!userToken?.contentOrNull.isNullOrBlank()}"
    }
    fun parseSession(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["supported_country"]?.jsonPrimitive?.booleanOrNull != true)
            throw IOException("官方服务未接受当前地区，请检查所选节点")
        val token = root["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (root["user"] as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull
        if (token.isNullOrBlank() || token.length > 16384 || token.any { it == '\r' || it == '\n' })
            throw MissingGuestCredentials()
        return token
    }
    fun parseCheckout(text: String, network: String = "J"): String {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["content_id"]?.jsonPrimitive?.contentOrNull != "ott_${network}_h264" ||
            root["protocol"]?.jsonPrimitive?.contentOrNull != "dash" ||
            root["drm"]?.jsonPrimitive?.contentOrNull != "cenc_m" ||
            root["video_stage"]?.jsonPrimitive?.contentOrNull != "free")
            throw IOException("官方播放配置不受支持（仅接入免费 TVB DASH）")
        val profiles = root["profiles"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val profile = profiles.firstOrNull { it["quality"]?.jsonPrimitive?.contentOrNull == "auto" }
            ?: profiles.firstOrNull { it["quality"]?.jsonPrimitive?.contentOrNull == "high" }
            ?: throw IOException("官方未返回播放配置")
        val url = profile["streaming_path"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
            ?: throw IOException("官方未返回播放地址")
        if (url.scheme != "https" || url.host != "edgeware-live.edgeware.tvb.com" ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            !url.encodedPath.endsWith("/index.mpd")) throw IOException("官方播放地址校验失败")
        return url.toString()
    }
    fun licenseClient(base: OkHttpClient, log: com.tvatlas.player.debug.DebugLog? = null): OkHttpClient = base.newBuilder()
        .followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            if (request.header("X-User-Token") != null && request.url.toString() !in channels.map { "https://wv.drm.tvb.com/wvproxy/mlicense?contentid=ott_${it.network}_h264" })
                throw IOException("拒绝向其他地址发送官方授权凭据")
            val started = System.nanoTime()
            val stage = if (request.header("X-User-Token") != null) "DRM_LICENSE" else "DRM_PROVISION"
            log?.event("INFO", stage, "start host=${request.url.host} method=${request.method} requestBytes=${request.body?.contentLength() ?: 0}")
            try {
                val response = chain.proceed(request)
                log?.event("INFO", stage, "status=${response.code} elapsedMs=${(System.nanoTime() - started) / 1000000}")
                response
            } catch (error: IOException) {
                log?.error(stage, error)
                throw error
            }
        }.build()
    suspend fun resolve(base: OkHttpClient, log: com.tvatlas.player.debug.DebugLog? = null, page: String = PAGE): Playback {
        val entry = channel(page) ?: throw IOException("不支持的官方频道")
        val cookies = mutableListOf<Cookie>()
        val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS).cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
                    values.forEach { c -> cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; cookies.add(c) }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                    cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
                }
            }).build()
        try {
            suspend fun fetchSession(): String {
                val response = request(client, Request.Builder()
                    .url("https://www.mytvsuper.com/api/auth/getSession/self/?sub=live")
                    .header("Accept", "application/json").header("Referer", PAGE).build(), "访客会话", log)
                log?.event("INFO", "SESSION", sessionSummary(response) +
                    " authCookiePresent=" + synchronized(cookies) { cookies.any { it.name == "auth" && it.expiresAt > System.currentTimeMillis() } })
                return response
            }
            val token = try { parseSession(fetchSession()) } catch (_: MissingGuestCredentials) {
                log?.event("INFO", "SESSION_BOOTSTRAP", "missing credentials; pair anonymous guest device once")
                // Public frontend pairDevice guest branch: no account/profile identifiers or persisted credentials.
                // Match its 16 random decimal digits + epoch seconds tracking identifier shape.
                val deviceId = kotlin.random.Random.nextLong(1000000000000000L, 10000000000000000L).toString() +
                    (System.currentTimeMillis() / 1000).toString()
                val pairingBody = buildJsonObject {
                    put("device_id", deviceId)
                    put("lang", "tc")
                    put("incognito", false)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val paired = request(client, Request.Builder()
                    .url("https://www.mytvsuper.com/api/auth/pairDevice/")
                    .header("Accept", "application/json").header("Origin", "https://www.mytvsuper.com")
                    .header("Referer", PAGE).post(pairingBody).build(), "访客设备配对", log)
                val pairing = Json.parseToJsonElement(paired) as? JsonObject
                    ?: throw IOException("官方访客配对响应格式不受支持")
                val hasErrors = pairing["errors"] != null && pairing["errors"] != JsonNull
                val success = (pairing["success"] as? JsonPrimitive)?.booleanOrNull
                log?.event("INFO", "SESSION_PAIR", "hasErrors=$hasErrors success=$success")
                if (hasErrors || success == false) throw IOException("官方未接受访客设备配对，请导出日志")
                val initialized = fetchSession()
                try { parseSession(initialized) } catch (_: MissingGuestCredentials) {
                    throw IOException("官方访客配对后仍未返回有效凭据，请导出日志")
                }
            }
            log?.event("INFO", "SESSION", "accepted region; received session credentials")
            val checkout = request(client, Request.Builder()
                .url("https://user-api.mytvsuper.com/v1/channel/checkout?platform=web&country_code=HK&network_code=${entry.network}")
                .header("Accept", "application/json").header("Referer", PAGE)
                .header("Origin", "https://www.mytvsuper.com")
                .header("App-Domain", "com.tvb.mytvsuper.web").header("Authorization", "Bearer $token").build(), "播放配置", log)
            val url = parseCheckout(checkout, entry.network)
            log?.event("INFO", "CHECKOUT", "free ${entry.network} DASH cenc_m automatic profile accepted")
            return Playback(url, token, "https://wv.drm.tvb.com/wvproxy/mlicense?contentid=ott_${entry.network}_h264")
        } finally { synchronized(cookies) { cookies.clear() } }
    }
    private suspend fun request(client: OkHttpClient, request: Request, stage: String, log: com.tvatlas.player.debug.DebugLog?): String = suspendCancellableCoroutine { continuation ->
        val started = System.nanoTime()
        log?.event("INFO", "HTTP", "$stage start host=${request.url.host} method=${request.method}")
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log?.event(if (call.isCanceled()) "INFO" else "ERROR", "HTTP", "$stage cancelled=${call.isCanceled()} exception=${e.javaClass.simpleName} elapsedMs=${(System.nanoTime() - started) / 1000000}")
                if (continuation.isActive) continuation.resumeWithException(IOException("$stage 连接失败，请检查网络和节点"))
            }
            override fun onResponse(call: Call, response: Response) {
                log?.event("INFO", "HTTP", "$stage status=${response.code} elapsedMs=${(System.nanoTime() - started) / 1000000}")
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("$stage 请求失败（HTTP ${it.code}）")
                        val body = it.body ?: throw IOException("官方返回空响应")
                        val bytes = body.byteStream().use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (out.size() + count > 2 * 1024 * 1024) throw IOException("官方响应超过限制")
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }
}
