package com.tvatlas.player.vod

import com.tvatlas.core.vod.CatalogEpisode
import com.tvatlas.player.network.readCancellable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A small media-prefix probe ranks reachable lines; playback still verifies manifests and segments. */
internal class VodLineSelector(private val client: OkHttpClient, private val clients: ((CatalogEpisode) -> OkHttpClient)? = null) {
    suspend fun order(candidates: List<CatalogEpisode>): List<CatalogEpisode> = coroutineScope {
        val unique = candidates.distinctBy { it.url }.sortedByDescending { it.qualityHeight }
        if (unique.size <= 1) return@coroutineScope unique
        val permits = Semaphore(4)
        val measured = unique.take(8).map { episode -> async(Dispatchers.IO) {
            permits.withPermit {
                val started = System.nanoTime()
                var height = episode.qualityHeight
                val network = (clients?.invoke(episode) ?: client).newBuilder().callTimeout(4, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
                val valid = try {
                    network.newCall(Request.Builder().url(episode.url).header("User-Agent", "Mozilla/5.0 TVAtlas").build()).readCancellable { response ->
                        if (!response.isSuccessful) false else {
                            val body = response.body ?: return@readCancellable false
                            val source = body.source()
                            source.request(4096)
                            val prefix = source.readByteArray(minOf(source.buffer.size, 4096))
                            val count = prefix.size
                            val text = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                            val measuredHeight = Regex("(?i)RESOLUTION=\\d+x(\\d+)").findAll(text)
                                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                            if (measuredHeight != null) height = measuredHeight
                            count > 0 && !text.startsWith("<html", true) && !text.startsWith("<!doctype", true) &&
                                (text.startsWith("#EXTM3U") || text.contains("<MPD") || text.startsWith("<?xml") && text.contains("MPD") ||
                                    body.contentType()?.type in setOf("video", "audio") ||
                                    count >= 8 && prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" ||
                                    count >= 4 && prefix[0] == 0x1a.toByte() && prefix[1] == 0x45.toByte() && prefix[2] == 0xdf.toByte() && prefix[3] == 0xa3.toByte())
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                Triple(episode, if (valid) (System.nanoTime() - started) else Long.MAX_VALUE, height)
            }
        } }.awaitAll()
        measured.filter { it.second != Long.MAX_VALUE }.sortedWith(compareByDescending<Triple<CatalogEpisode, Long, Int>> { it.third }.thenBy { it.second }).map { it.first } +
            unique.drop(8) + measured.filter { it.second == Long.MAX_VALUE }.map { it.first }
    }
}
