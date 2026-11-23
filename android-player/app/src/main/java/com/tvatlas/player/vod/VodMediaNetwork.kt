package com.tvatlas.player.vod

import okhttp3.OkHttpClient
import com.tvatlas.core.vod.ConfirmedAdPlaylistSession
import com.tvatlas.core.vod.ConfirmedAdMatch
import com.tvatlas.core.vod.ConfirmedVodAds

internal fun vodMediaClient(transport: OkHttpClient, canPlay: () -> Boolean, log: com.tvatlas.player.debug.DebugLog?, onConfirmedAds: ((ConfirmedAdMatch?) -> Unit)? = null, adSession: ConfirmedAdPlaylistSession? = null, onAdEvidence: ((Boolean) -> Unit)? = null) = transport.newBuilder().followRedirects(true).followSslRedirects(true)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val type = response.header("Content-Type").orEmpty().lowercase()
            if ((onAdEvidence != null || onConfirmedAds != null) && response.isSuccessful && (response.request.url.encodedPath.endsWith(".m3u8", true) || "mpegurl" in type)) {
                // Peek the existing request only. Playback bytes and the media timeline stay unchanged.
                val candidate = adSession?.accepts(response.request.url.toString()) == true
                val text = response.peekBody(if (candidate) ConfirmedVodAds.MAX_BYTES.toLong() + 1 else 65536).string()
                if (text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")) {
                    onAdEvidence?.invoke(hasVodInsertionMarkers(text))
                    if (candidate && onConfirmedAds != null) {
                        val result = adSession?.inspect(response.request.url.toString(), text)
                        if (result?.relevant == true) onConfirmedAds(result.match)
                    }
                }
            }
            response
        }.addNetworkInterceptor { chain ->
            if (!canPlay()) throw java.io.IOException("Network policy does not allow playback")
            val started = (System.nanoTime() / 1_000_000)
            try {
                val response = chain.proceed(chain.request())
                if (!response.isSuccessful || response.request.url.encodedPath.endsWith(".m3u8", true) || response.request.url.encodedPath.endsWith(".mpd", true))
                    log?.event(if (response.isSuccessful) "INFO" else "WARN", "VOD_HTTP",
                        "host=${response.request.url.host} status=${response.code} elapsedMs=${(System.nanoTime() / 1_000_000) - started}")
                response
            } catch (error: java.io.IOException) {
                log?.event("ERROR", "VOD_HTTP", "host=${chain.request().url.host} exception=${error.javaClass.simpleName}")
                throw error
            }
        }.build()

/** Markers indicate insertion metadata, not proof of an advertisement. */
internal fun hasVodInsertionMarkers(text: String): Boolean = text.lineSequence().any { line ->
    val row = line.trim()
    row.startsWith("#EXT-X-CUE-OUT:") || row == "#EXT-X-CUE-OUT" ||
        row.startsWith("#EXT-OATCLS-SCTE35:") ||
        (row.startsWith("#EXT-X-DATERANGE:") &&
            ("SCTE35-OUT=" in row || "CLASS=\"com.apple.hls.interstitial\"" in row))
}
