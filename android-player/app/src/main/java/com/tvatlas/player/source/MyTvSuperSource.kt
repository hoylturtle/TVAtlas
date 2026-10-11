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

/** Only the official Jade page is treated as a dynamic source. Never persist session credentials. */
object MyTvSuperSource {
    const val PAGE = "https://www.mytvsuper.com/tc/live/81/"
    const val PLAYLIST = "#EXTM3U\n#EXTINF:-1 tvg-id=\"Jade.hk\" group-title=\"香港\",翡翠台\n$PAGE\n"
    const val LICENSE = "https://wv.drm.tvb.com/wvproxy/mlicense?contentid=ott_J_h264"
    fun recognizes(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == "www.mytvsuper.com" && url.username.isEmpty() &&
            url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath.trimEnd('/') in setOf("/tc/live/81", "/tc/live/81/%E7%BF%A1%E7%BF%A0%E5%8F%B0")
    }
    data class Playback(val url: String, val userToken: String)
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
    fun parseCheckout(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["content_id"]?.jsonPrimitive?.contentOrNull != "ott_J_h264" ||
            root["protocol"]?.jsonPrimitive?.contentOrNull != "dash" ||
            root["drm"]?.jsonPrimitive?.contentOrNull != "cenc_m" ||
            root["video_stage"]?.jsonPrimitive?.contentOrNull != "free")
            throw IOException("官方播放配置不受支持（仅接入免费翡翠台 DASH）")
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
            if (request.header("X-User-Token") != null && request.url.toString() != LICENSE)
                throw IOException("拒绝向其他地址发送官方授权凭据")
            val started = System.nanoTime()
            val stage = if (request.header("X-User-Token") != null) "DRM_LICENSE" else "DRM_PROVISION"
            log?.event("INFO", stage, "start host=${request.url.host} method=${request.method}")
            try {
                val response = chain.proceed(request)
                log?.event("INFO", stage, "status=${response.code} elapsedMs=${(System.nanoTime() - started) / 1000000}")
                response
            } catch (error: IOException) {
                log?.error(stage, error)
                throw error
            }
        }.build()
    suspend fun resolve(base: OkHttpClient, log: com.tvatlas.player.debug.DebugLog? = null): Playback {
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
                .url("https://user-api.mytvsuper.com/v1/channel/checkout?platform=web&country_code=HK&network_code=J")
                .header("Accept", "application/json").header("Referer", PAGE)
                .header("Origin", "https://www.mytvsuper.com")
                .header("App-Domain", "com.tvb.mytvsuper.web").header("Authorization", "Bearer $token").build(), "播放配置", log)
            val url = parseCheckout(checkout)
            log?.event("INFO", "CHECKOUT", "free Jade DASH cenc_m automatic profile accepted")
            return Playback(url, token)
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
