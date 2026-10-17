package com.tvatlas.player.vod

import okhttp3.OkHttpClient

internal fun vodMediaClient(transport: OkHttpClient, canPlay: () -> Boolean, log: com.tvatlas.player.debug.DebugLog?) = transport.newBuilder().followRedirects(true).followSslRedirects(true)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).addNetworkInterceptor { chain ->
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
