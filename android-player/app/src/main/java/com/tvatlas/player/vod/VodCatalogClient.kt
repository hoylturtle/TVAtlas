package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import com.tvatlas.player.network.readCancellable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

class VodCatalogClient(client: OkHttpClient, private val log: com.tvatlas.player.debug.DebugLog? = null, private val eventTag: String = "VOD_CATALOG") {
    private val client = client.newBuilder().followRedirects(true).followSslRedirects(true).callTimeout(25, TimeUnit.SECONDS).build()
    suspend fun load(api: String, page: Int, search: String, id: String? = null, type: String? = null, listingOnly: Boolean = false, year: String? = null, area: String? = null): CatalogPage {
        if (api == ArchiveVodCatalog.SOURCE) {
            log?.event("INFO", eventTag, "start host=archive.org publicDomain=true")
            return try {
                ArchiveVodCatalog.load(client, page, search, id, type, listingOnly, year, area).also {
                    log?.event("INFO", eventTag, "loaded items=${it.movies.size} mediaAddressItems=${it.movies.count { movie -> movie.episodes.isNotEmpty() }}")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { log?.error(eventTag, error); throw error }
        }
        val url = api.toHttpUrl().newBuilder().setQueryParameter("ac", if (listingOnly) "list" else "detail")
            .setQueryParameter("pg", page.toString()).apply {
                if (search.isNotBlank()) setQueryParameter("wd", search.take(100))
                if (id != null) setQueryParameter("ids", id)
                if (type != null) setQueryParameter("t", type)
                if (year != null) setQueryParameter("year", year)
                if (area != null) setQueryParameter("area", area)
            }.build()
        log?.event("INFO", eventTag, "start host=${url.host}")
        return try {
            client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 TVAtlas").build()).readCancellable { response ->
                log?.event("INFO", "${eventTag}_HTTP", "host=${response.request.url.host} status=${response.code}")
                require(response.isSuccessful) { "点播接口返回 HTTP ${response.code}" }
                val input = requireNotNull(response.body).byteStream()
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 4 * 1024 * 1024) { "片单响应过大" }
                    out.write(buffer, 0, count)
                }
                VodCatalog.parse(out.toString("UTF-8")).also {
                    log?.event("INFO", eventTag, "loaded items=${it.movies.size} mediaAddressItems=${it.movies.count { movie -> movie.episodes.isNotEmpty() }}")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log?.error(eventTag, error)
            throw error
        }
    }
}
