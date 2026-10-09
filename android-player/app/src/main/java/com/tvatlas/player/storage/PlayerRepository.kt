package com.tvatlas.player.storage

import androidx.room.withTransaction
import com.tvatlas.core.model.*
import com.tvatlas.core.playlist.M3uParser
import com.tvatlas.core.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

data class Library(val playlists: List<Playlist>, val channels: List<Channel>, val profiles: List<ProxyProfile>, val rules: RuleConfig)

class PlayerRepository(private val db: PlayerDatabase, private val directClient: OkHttpClient, private val log: com.tvatlas.player.debug.DebugLog? = null) {
    private val dao = db.dao()
    private fun route(text: String?): RouteTarget? = text?.let { RuleCodec.json.decodeFromString<RouteTarget>(it) }
    private fun encoded(route: RouteTarget?) = route?.let { RuleCodec.json.encodeToString(it) }
    val library = combine(dao.playlists(), dao.channels(), dao.streams(), dao.proxies(), dao.rules()) { p, c, s, proxies, rules ->
        val enabled = p.filter { it.enabled }.map { it.id }.toSet()
        val channels = c.mapNotNull { ch ->
            val streams = s.filter { it.channelId == ch.id && it.sourcePlaylistId in enabled }.distinctBy { it.url }.map {
                Stream(it.id, it.url, it.sourcePlaylistId, route(it.manualRoute), it.lastSuccessAt, it.lastFailureAt, it.failureCount)
            }
            if (streams.isEmpty()) null else Channel(ch.id, ch.name, ch.channelGroup, ch.logo, ch.tvgId, streams, route(ch.manualRoute))
        }
        Library(p.map { Playlist(it.id, it.name, it.url, it.enabled, it.refreshIntervalHours, it.lastUpdatedAt) }, channels,
            proxies.map { ProxyProfile(it.id, it.name, ProxyType.valueOf(it.type), it.host, it.port, it.enabled, it.subscriptionId) },
            rules?.let { RuleCodec.json.decodeFromString<RuleConfig>(it.json) } ?: RuleConfig())
    }

    suspend fun refresh(playlist: Playlist) = withContext(Dispatchers.IO) {
        require(httpUri(playlist.url) != null) { "请输入不含凭证的 HTTP(S) 地址" }
        val body = if (com.tvatlas.player.source.MyTvSuperSource.recognizes(playlist.url)) com.tvatlas.player.source.MyTvSuperSource.PLAYLIST else directClient.newCall(Request.Builder().url(playlist.url).build()).execute().use { response ->
            log?.event("INFO", "PLAYLIST_HTTP", "status=${response.code} host=${response.request.url.host}")
            require(response.isSuccessful) { "播放列表请求失败（HTTP ${response.code}）" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 8 * 1024 * 1024) { "播放列表超过 8 MB" }
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8 * 1024 * 1024) { "播放列表超过 8 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 8 * 1024 * 1024) { "播放列表超过 8 MB" }
            bytes.toString(Charsets.UTF_8)
        }
        val parsed = M3uParser.parse(body, playlist.id)
        require(parsed.isNotEmpty()) { "没有有效的 HTTP(S) 频道，已保留旧列表" }
        db.withTransaction {
            val oldStreams = dao.sourceStreams(playlist.id).associateBy { it.id }
            val rows = parsed.flatMap { ch ->
                val previous = dao.channel(ch.id)
                dao.saveChannel(ChannelRow(ch.id, ch.name, ch.group, ch.logo, ch.tvgId, previous?.manualRoute))
                ch.streams.map { stream ->
                    oldStreams[stream.id]?.copy(url = stream.url) ?: StreamRow(stream.id, ch.id, stream.url, playlist.id)
                }
            }
            dao.deleteStreams(oldStreams.keys.toList() - rows.map { it.id }.toSet())
            dao.saveStreams(rows)
            dao.pruneHistory(); dao.pruneFailures()
            dao.savePlaylist(PlaylistRow(playlist.id, playlist.name, playlist.url, playlist.enabled,
                playlist.refreshIntervalHours, System.currentTimeMillis()))
        }
    }

    suspend fun saveProxy(profile: ProxyProfile) {
        RuleCodec.validateProxy(profile)
        dao.saveProxies(listOf(ProxyRow(profile.id, profile.name, profile.type.name, profile.host, profile.port, profile.enabled, profile.subscriptionId)))
    }
    suspend fun importRules(text: String, profiles: List<ProxyProfile>): RuleConfig {
        val config = withContext(Dispatchers.Default) { RuleCodec.parse(text, profiles) }
        db.withTransaction {
            dao.saveProxies(config.proxies.map { ProxyRow(it.id, it.name, it.type.name, it.host, it.port, it.enabled) })
            dao.saveRules(RulesRow(json = RuleCodec.export(config)))
        }
        return config
    }
    suspend fun setChannelRoute(id: String, target: RouteTarget?) = dao.channelRoute(id, encoded(target))
    suspend fun setStreamRoute(id: String, target: RouteTarget?) = dao.streamRoute(id, encoded(target))
    suspend fun preferred(channelId: String): SuccessfulRoute? = dao.history(channelId)?.let {
        SuccessfulRoute(it.streamId, requireNotNull(route(it.target)), it.at)
    }
    suspend fun recordSuccess(channelId: String, attempt: RouteAttempt) = db.withTransaction {
        val at = System.currentTimeMillis()
        if (dao.stream(attempt.streamId) != null) {
            dao.saveHistory(HistoryRow(channelId, attempt.streamId, encoded(attempt.target)!!, at))
            dao.success(attempt.streamId, at)
        }
    }
    suspend fun recordFailure(channelId: String, attempt: RouteAttempt, reason: String) = db.withTransaction {
        val at = System.currentTimeMillis()
        if (dao.stream(attempt.streamId) != null) {
            // Only controlled category/status values are persisted. Exception messages and URLs are excluded.
            dao.saveFailure(FailureRow(channelId, attempt.streamId, encoded(attempt.target)!!, reason, at))
            dao.failure(attempt.streamId, at)
        }
    }
}
