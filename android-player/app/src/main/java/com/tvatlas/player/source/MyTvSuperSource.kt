package com.tvatlas.player.source

import com.tvatlas.core.player.*

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
    const val PLAYLIST = "#EXTM3U\n#EXTINF:-1 tvg-id=\"Jade.hk\" group-title=\"香港 · 综合\",翡翠台\n$PAGE\n"
    const val PEARL_PAGE = "https://www.mytvsuper.com/tc/live/84/"
    const val PLUS_PAGE = "https://www.mytvsuper.com/tc/live/82/"
    const val FREE_PAGE = "https://www.mytvsuper.com/tc/live/"
    data class OfficialChannel(val page: String, val network: String, val name: String, val tvgId: String, val group: String)
    // Public official Freezone catalog, checked 2026-10-12. Paid and mainland channels are excluded.
    val channels = listOf(
        OfficialChannel("https://www.mytvsuper.com/tc/live/81/", "J", "翡翠台", "Jade.hk", "香港 · 综合"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/84/", "P", "明珠台", "Pearl.hk", "香港 · 综合"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/82/", "B", "TVB Plus", "TVBPlus.hk", "香港 · 综合"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/1/", "CWIN", "SUPER FREE", "CWIN.hk", "香港 · 综合"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/2/", "SVAR", "SUPER Game Show", "SVAR.hk", "香港 · 综艺"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/3/", "SEYT", "愛．回家SUPER煲", "SEYT.hk", "香港 · 影视"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/4/", "SFOO", "SUPER識食", "SFOO.hk", "香港 · 纪实"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/5/", "STRA", "SUPER資訊娛樂", "STRA.hk", "香港 · 综艺"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/6/", "SMUS", "SUPER Music", "SMUS.hk", "香港 · 音乐"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/7/", "SGOL", "SUPER靈異", "SGOL.hk", "香港 · 影视"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/8/", "SSIT", "SUPER處境劇", "SSIT.hk", "香港 · 影视"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/9/", "STVM", "SUPER亞視劇", "STVM.hk", "香港 · 影视"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/10/", "SDOC", "真情SUPER煲", "SDOC.hk", "香港 · 影视"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/11/", "SSPT", "SUPER Sports", "SSPT.hk", "香港 · 体育"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/80/", "TVG", "黄金翡翠台", "TVG.hk", "香港 · 综合"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/83/", "C", "无线新闻台", "C.hk", "香港 · 新闻"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/91/", "CTVE", "娱乐新闻台", "CTVE.hk", "香港 · 新闻"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/207/", "ACTM", "Action Hollywood Movies", "ACTM.hk", "香港 · 影视"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/208/", "RCM", "Rialto Classic Movies (RCM)", "RCM.hk", "香港 · 影视"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/305/", "TRSP", "TRACE Sport Stars", "TRSP.hk", "香港 · 体育"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/408/", "PETC", "Pet Club TV", "PETC.hk", "香港 · 纪实"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/409/", "GLBT", "Globetrotter", "GLBT.hk", "香港 · 纪实"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/410/", "DOCV", "Docsville", "DOCV.hk", "香港 · 纪实"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/411/", "PULS", "Wild Stuff", "PULS.hk", "香港 · 纪实"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/626/", "NOW7", "that's 70s", "NOW7.hk", "香港 · 音乐"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/627/", "NOW8", "that's 80s", "NOW8.hk", "香港 · 音乐"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/628/", "NOWR", "that's Rock", "NOWR.hk", "香港 · 音乐"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/629/", "NOW9", "that's 90s00s", "NOW9.hk", "香港 · 音乐"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/630/", "CONC", "Concerto", "CONC.hk", "香港 · 音乐"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/631/", "TRUR", "TRACE Urban", "TRUR.hk", "香港 · 音乐"),
        OfficialChannel("https://www.mytvsuper.com/tc/live/709/", "NSWD", "NewsWorld", "NSWD.hk", "香港 · 新闻")
    )
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
        val entries = if (value.trimEnd('/') == FREE_PAGE.trimEnd('/')) channels else listOf(requireNotNull(channel(value)))
        return "#EXTM3U\n" + entries.joinToString("") { entry ->
            "#EXTINF:-1 tvg-id=\"${entry.tvgId}\" group-title=\"${entry.group}\",${entry.name}\n${entry.page}\n"
        }
    }
    const val LICENSE = "https://wv.drm.tvb.com/wvproxy/mlicense?contentid=ott_J_h264"
    fun licenseHeaders(userToken: String) = mapOf(
        "X-User-Token" to userToken, "X-Client-Platform" to "web_android",
        "X-Service-Id" to "super", "Content-Type" to "application/octet-stream",
        "Origin" to "https://www.mytvsuper.com", "Referer" to PAGE,
    )
    fun recognizes(value: String): Boolean = channel(value) != null
    data class Playback(val url: String, val userToken: String, val licenseUrl: String = LICENSE, val drmRequired: Boolean = true,
        val mimeType: String = "application/dash+xml")
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
            throw SourceResolutionException("官方服务未接受当前地区，请检查所选节点", PlaybackFailure(FailureKind.HTTP, 403))
        val token = root["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (root["user"] as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull
        if (token.isNullOrBlank() || token.length > 16384 || token.any { it == '\r' || it == '\n' })
            throw MissingGuestCredentials()
        return token
    }
    fun parseCheckout(text: String, network: String = "J", log: com.tvatlas.player.debug.DebugLog? = null): String =
        parseCheckoutConfig(text, network, log).url
    fun parseCheckoutConfig(text: String, network: String = "J", log: com.tvatlas.player.debug.DebugLog? = null): Playback {
        val root = Json.parseToJsonElement(text).jsonObject
        val protocol = root["protocol"]?.jsonPrimitive?.contentOrNull
        val drm = root["drm"]?.jsonPrimitive?.contentOrNull
        val protected = drm == "cenc_m"
        if (root["content_id"]?.jsonPrimitive?.contentOrNull != "ott_${network}_h264" ||
            protocol !in listOf("dash", "hls") || drm !in listOf(null, "", "none", "cenc_m") ||
            (protected && protocol != "dash") || root["video_stage"]?.jsonPrimitive?.contentOrNull != "free")
            throw SourceResolutionException("官方播放配置不受支持（仅接入免费 DASH / HLS）", PlaybackFailure(FailureKind.MANIFEST))
        val profiles = root["profiles"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val profile = profiles.firstOrNull { it["quality"]?.jsonPrimitive?.contentOrNull == "auto" }
            ?: profiles.firstOrNull { it["quality"]?.jsonPrimitive?.contentOrNull == "high" }
            ?: throw SourceResolutionException("官方未返回播放配置", PlaybackFailure(FailureKind.MANIFEST))
        val url = profile["streaming_path"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
            ?: throw SourceResolutionException("官方未返回播放地址", PlaybackFailure(FailureKind.MANIFEST))
        // Checkout can return the entry host or a direct session host on the same official CDN.
        val trustedHost = url.host == "edgeware.tvb.com" || url.host.endsWith(".edgeware.tvb.com") ||
            Regex("""amg[0-9]+-amg[0-9]+c[0-9]+-mytvsuper-apac-[0-9]+\.playouts\.now\.amagi\.tv""").matches(url.host)
        val reason = when {
            url.scheme != "https" -> "scheme"
            !trustedHost -> "host"
            url.port != 443 -> "port"
            url.username.isNotEmpty() || url.password.isNotEmpty() -> "credentials"
            url.fragment != null -> "fragment"
            url.encodedPath.trim('/').isEmpty() -> "manifest"
            else -> null
        }
        log?.event(if (reason == null) "INFO" else "WARN", "CHECKOUT_URL",
            "host=${url.host} accepted=${reason == null} reason=${reason ?: "none"}")
        if (reason != null) throw SourceResolutionException("官方播放地址校验失败（$reason），请导出日志", PlaybackFailure(FailureKind.MANIFEST))
        // Third-party official CDN profiles may use HLS despite a generic DASH checkout label,
        // or extensionless manifest endpoints. The trusted host is the security boundary,
        // not a filename suffix. Prefer an explicit manifest extension when present.
        val path = url.encodedPath.trimEnd('/').lowercase(java.util.Locale.ROOT)
        val mediaProtocol = when {
            path.endsWith(".m3u8") -> "hls"
            path.endsWith(".mpd") -> "dash"
            else -> protocol
        }
        log?.event("INFO", "CHECKOUT_FORMAT", "declared=$protocol selected=$mediaProtocol drm=$protected extension=${when {
            path.endsWith(".m3u8") -> "m3u8"
            path.endsWith(".mpd") -> "mpd"
            else -> "other"
        }}")
        return Playback(url.toString(), "", drmRequired = protected, mimeType = if (mediaProtocol == "hls") "application/x-mpegURL" else "application/dash+xml")
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
            val playback = parseCheckoutConfig(checkout, entry.network, log)
            log?.event("INFO", "CHECKOUT", "free ${entry.network} mime=${playback.mimeType} drm=${playback.drmRequired} automatic profile accepted")
            return playback.copy(userToken = token, licenseUrl = "https://wv.drm.tvb.com/wvproxy/mlicense?contentid=ott_${entry.network}_h264")
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
                if (continuation.isActive) continuation.resumeWithException(SourceResolutionException("$stage 连接失败，请检查网络和节点", PlaybackFailure(FailureKind.NETWORK), e))
            }
            override fun onResponse(call: Call, response: Response) {
                log?.event("INFO", "HTTP", "$stage status=${response.code} elapsedMs=${(System.nanoTime() - started) / 1000000}")
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw SourceResolutionException("$stage 请求失败（HTTP ${it.code}）", PlaybackFailure(FailureKind.HTTP, it.code))
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
