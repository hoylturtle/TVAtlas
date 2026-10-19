package com.tvatlas.player.vod

import okhttp3.OkHttpClient

internal fun vodMediaClient(transport: OkHttpClient, canPlay: () -> Boolean, log: com.tvatlas.player.debug.DebugLog?, onAdEvidence: ((Boolean) -> Unit)? = null) = transport.newBuilder().followRedirects(true).followSslRedirects(true)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (onAdEvidence != null && response.isSuccessful && (response.request.url.encodedPath.endsWith(".m3u8", true) || "mpegurl" in type)) {
                // Inspect only a bounded prefix of an already requested playlist. Never remove segments.
                val text = response.peekBody(65536).string()
                if (text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U"))
                    onAdEvidence(hasVodInsertionMarkers(text))
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
