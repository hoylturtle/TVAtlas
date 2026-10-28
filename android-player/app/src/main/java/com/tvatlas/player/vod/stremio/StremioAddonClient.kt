package com.tvatlas.player.vod.stremio

import com.tvatlas.core.model.httpUri
import com.tvatlas.core.vod.*
import com.tvatlas.core.vod.stremio.*
import com.tvatlas.player.network.readCancellable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

internal class StremioAddonClient(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build()
    companion object {
        fun address(input: String): String {
            val value = input.trim().replaceFirst(Regex("^stremio://"), "https://")
            require(value.length <= 4096 && httpUri(value) != null) { "请输入 HTTP(S) manifest.json 插件地址" }
            val url = try { value.toHttpUrl() } catch (_: IllegalArgumentException) { throw IllegalArgumentException("插件地址格式无效") }
            require(url.encodedPath.endsWith("/manifest.json") && url.fragment == null && url.query == null) { "插件地址应以 /manifest.json 结尾；请使用配置后的完整插件地址" }
            return url.toString()
        }
        fun resource(address: String, resource: String, type: String, id: String, extra: Map<String, String> = emptyMap()): HttpUrl {
            require(resource in setOf("catalog", "meta", "stream"))
            val builder = address.toHttpUrl().newBuilder().removePathSegment(address.toHttpUrl().pathSegments.lastIndex)
                .addPathSegment(resource).addPathSegment(type).addPathSegment(if (extra.isEmpty()) "$id.json" else id)
            if (extra.isNotEmpty()) builder.addEncodedPathSegment(extra.entries.joinToString("&") { (k,v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20") } + ".json")
            return builder.build()
        }
    }
    private suspend fun read(url: HttpUrl): String = client.newCall(Request.Builder().url(url).header("User-Agent", "TVAtlas addon client").build()).readCancellable { response ->
        require(response.isSuccessful) { "插件返回 HTTP ${response.code}，请检查地址或插件配置" }
        val input = requireNotNull(response.body).byteStream(); val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 4 * 1024 * 1024) { "插件响应过大" }; out.write(buffer, 0, n) }
        out.toString("UTF-8")
    }
    private suspend fun readEntry(entry: AddonEntry, resource: String, type: String, id: String, extra: Map<String, String> = emptyMap()): String =
        if (entry.address == OpenFilmsAddon.ADDRESS) OpenFilmsAddon.response(resource, type, id, extra["search"].orEmpty())
        else read(resource(entry.address, resource, type, id, extra))
    suspend fun manifest(address: String) = withContext(Dispatchers.Default) { StremioProtocol.manifest(read(address.toHttpUrl())).also {
        require(!it.configurationRequired) { "此插件需要先配置，请添加配置完成后的地址" }
    } }
    suspend fun catalog(entry: AddonEntry, page: Int, query: String, id: String?, type: String?, offset: Int = 0): CatalogPage {
        val kind = type?.takeIf { it in setOf("movie", "series") } ?: id?.substringBefore(':') ?: "movie"
        if (id != null) {
            val rawId = id.substringAfter(':')
            if (entry.manifest.supports("meta", kind, rawId)) {
                val text = readEntry(entry, "meta", kind, rawId)
                val movie = withContext(Dispatchers.Default) { StremioProtocol.detail(text, entry.source, kind) }
                require(movie.id == id) { "插件返回了不匹配的影片" }
                return CatalogPage(listOf(movie), 1, 1)
            }
            return CatalogPage(emptyList(), 1, 1)
        }
        val catalog = entry.manifest.catalogs.firstOrNull { it.type == kind && it.required.all { key -> key == "search" && query.isNotBlank() } && (query.isBlank() || it.search) }
            ?: return CatalogPage(emptyList(), page, page)
        if (page > 1 && !catalog.skip) return CatalogPage(emptyList(), page, page)
        val extra = linkedMapOf<String, String>()
        if (query.isNotBlank()) extra["search"] = query.take(100)
        if (catalog.skip && page > 1) extra["skip"] = offset.toString()
        val text = readEntry(entry, "catalog", kind, catalog.id, extra)
        val movies = withContext(Dispatchers.Default) { StremioProtocol.catalog(text, kind, entry.source) }
        return CatalogPage(movies, page, if (catalog.skip && movies.isNotEmpty()) page + 1 else page)
    }
    suspend fun streams(entry: AddonEntry, type: String, id: String, name: String): AddonStreams {
        if (!entry.manifest.supports("stream", type, id)) return AddonStreams(emptyList(), 0)
        val text = readEntry(entry, "stream", type, id)
        return withContext(Dispatchers.Default) { StremioProtocol.streams(text, name) }
    }
}

/** Addon-supplied request headers are scoped to the original media origin, including redirects. */
internal fun addonMediaClient(client: OkHttpClient, episode: CatalogEpisode): OkHttpClient {
    if (episode.headers.isEmpty()) return client
    val original = episode.url.toHttpUrl()
    return client.newBuilder().addNetworkInterceptor { chain ->
        val url = chain.request().url
        val request = chain.request().newBuilder()
        episode.headers.forEach { (name, value) ->
            if (url.scheme == original.scheme && url.host == original.host && url.port == original.port) request.header(name, value)
            else request.removeHeader(name)
        }
        chain.proceed(request.build())
    }.build()
}
