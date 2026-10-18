package com.tvatlas.player.storage

import com.tvatlas.player.network.readCancellable

import androidx.room.withTransaction
import com.tvatlas.core.model.*
import com.tvatlas.core.playlist.M3uParser
import com.tvatlas.core.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

data class Library(val playlists: List<Playlist>, val channels: List<Channel>, val profiles: List<ProxyProfile>, val rules: RuleConfig)

class PlayerRepository(private val db: PlayerDatabase, private val directClient: OkHttpClient, private val log: com.tvatlas.player.debug.DebugLog? = null,
    private val regionalCatalog: String = "",
    private val regionalCatalogLoader: (() -> String)? = null,
    private val fetch: (suspend (String) -> String)? = null) {
    private val dao = db.dao()
    private val regionalText by lazy { regionalCatalogLoader?.invoke() ?: regionalCatalog }
    private fun route(text: String?): RouteTarget? = text?.let { RuleCodec.json.decodeFromString<RouteTarget>(it) }
    private fun encoded(route: RouteTarget?) = route?.let { RuleCodec.json.encodeToString(it) }
    suspend fun initializeDefaults(defaults: List<Playlist> = DefaultPlaylists.entries,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        log?.event("INFO", "STARTUP_DEFAULTS", "begin")
        // Persist entries before downloading, so offline first launches still offer refresh.
        val pending = db.withTransaction {
            defaults.mapNotNull { entry ->
                val row = dao.playlist(entry.id) ?: PlaylistRow(entry.id, entry.name, entry.url).also {
                    dao.savePlaylist(it)
                }
                if (row.enabled && row.lastUpdatedAt == null)
                    Playlist(row.id, row.name, row.url, row.enabled, row.refreshIntervalHours)
                else null
            }
        }
        // Install the small local repair table before any network work, including on upgrades.
        defaults.firstOrNull { it.url == DefaultPlaylists.CHANNELS_URL }?.let { entry ->
            val row = dao.playlist(entry.id)
            if (row?.enabled == true && regionalText.isNotBlank()) {
                val playlist = Playlist(row.id, row.name, row.url, row.enabled, row.refreshIntervalHours, row.lastUpdatedAt)
                persist(playlist, M3uParser.parse(regionalText, entry.id), partial = true)
            }
        }
        log?.event("INFO", "STARTUP_DEFAULTS", "local ready elapsedMs=${(System.nanoTime() - started) / 1_000_000} networkPending=${pending.size}")
        onProgress(0, pending.size)
        for ((index, playlist) in pending.withIndex()) {
            try {
                refresh(playlist)
                log?.event("INFO", "DEFAULT_PLAYLIST", "loaded ${playlist.name}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { log?.error("DEFAULT_PLAYLIST", error) }
            onProgress(index + 1, pending.size)
        }
    }
    val ruleFiles = dao.ruleFiles()
    val library = combine(dao.playlists(), dao.channels(), dao.streams(), dao.proxies(), dao.rules()) { p, c, s, proxies, rules ->
        val enabled = p.filter { it.enabled }.map { it.id }.toSet()
        val byChannel = s.filter { it.sourcePlaylistId in enabled }.groupBy { it.channelId }
        val channels = c.mapNotNull { ch ->
            val hongKong = Channel(ch.id, ch.name, ch.channelGroup, ch.logo, ch.tvgId, emptyList()).isHongKong()
            val streams = byChannel[ch.id].orEmpty().filter { !hongKong ||
                com.tvatlas.player.source.MyTvSuperSource.recognizes(it.url) || com.tvatlas.player.source.ViuTvSource.recognizes(it.url) ||
                com.tvatlas.player.source.HongKongPublicSources.recognizesStream(it.url)
            }.distinctBy { it.url }.map {
                Stream(it.id, it.url, it.sourcePlaylistId, route(it.manualRoute), it.lastSuccessAt, it.lastFailureAt, it.failureCount, runCatching { RuleCodec.json.decodeFromString<Map<String, String>>(it.headers) }.getOrDefault(emptyMap()))
            }
            if (streams.isEmpty()) null else Channel(ch.id, ch.name, ch.channelGroup, ch.logo, ch.tvgId, streams, route(ch.manualRoute)).normalizeHongKongGroup()
        }
        Library(p.map { Playlist(it.id, it.name, it.url, it.enabled, it.refreshIntervalHours, it.lastUpdatedAt) }, channels,
            proxies.map { ProxyProfile(it.id, it.name, ProxyType.valueOf(it.type), it.host, it.port, it.enabled, it.subscriptionId) },
            rules?.let { RuleCodec.json.decodeFromString<RuleConfig>(it.json) } ?: RuleConfig())
    }.flowOn(Dispatchers.Default)

    suspend fun refresh(playlist: Playlist) = withContext(Dispatchers.IO) {
        require(httpUri(playlist.url) != null) { "请输入不含凭证的 HTTP(S) 地址" }
        val body = if (playlist.url.trimEnd('/') == com.tvatlas.player.source.MyTvSuperSource.FREE_PAGE.trimEnd('/') || com.tvatlas.player.source.MyTvSuperSource.recognizes(playlist.url)) com.tvatlas.player.source.MyTvSuperSource.playlist(playlist.url) else if (com.tvatlas.player.source.ViuTvSource.recognizes(playlist.url)) com.tvatlas.player.source.ViuTvSource.playlist(playlist.url) else if (com.tvatlas.player.source.HongKongPublicSources.recognizesPage(playlist.url)) com.tvatlas.player.source.HongKongPublicSources.playlist(playlist.url) else if (fetch != null) fetch.invoke(playlist.url) else directClient.newCall(Request.Builder().url(playlist.url).build()).readCancellable { response ->
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
        require(body.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "播放列表超过 8 MB" }
        val base = M3uParser.parse(body, playlist.id)
        val parsed = if (playlist.url == DefaultPlaylists.CHANNELS_URL)
            com.tvatlas.core.playlist.RegionalLiveCatalog.merge(base, M3uParser.parse(regionalText, playlist.id)) else base
        require(parsed.isNotEmpty()) { "没有有效的 HTTP(S) 频道，已保留旧列表" }
        if (playlist.id.startsWith("unified-live:")) {
            val otherStreams = (dao.managedStreamCount() - dao.sourceStreams(playlist.id).size).coerceAtLeast(0)
            com.tvatlas.core.vod.MediaSubscription.checkedLiveBudget(parsed.size, parsed.sumOf { it.streams.size }, otherStreams)
        }
        persist(playlist, parsed)
    }

    private suspend fun persist(playlist: Playlist, parsed: List<Channel>, partial: Boolean = false) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val oldStreams = dao.sourceStreams(playlist.id).associateBy { it.id }
            val previousChannels = dao.channelsSnapshot()
            val previousById = previousChannels.associateBy { it.id }
            val previousByName = previousChannels.groupBy { it.name }
            val oldByIdentity = oldStreams.values.groupBy { it.channelId to M3uParser.streamIdentity(it.url) }
            val rows = parsed.flatMap { ch ->
                // Retain legacy IDs only when identity/region is unambiguous, preserving favorites and routes.
                val previous = previousById[ch.id] ?: previousByName[ch.name].orEmpty().filter { row ->
                    (ch.tvgId == null || row.tvgId == null || ch.tvgId == row.tvgId) &&
                    Channel(row.id, row.name, row.channelGroup, row.logo, row.tvgId, emptyList()).playbackRegion() == ch.playbackRegion()
                }.singleOrNull()
                val channelId = previous?.id ?: ch.id
                val nextChannel = ChannelRow(channelId, ch.name, ch.group, ch.logo, ch.tvgId, previous?.manualRoute)
                if (nextChannel != previous) dao.saveChannel(nextChannel)
                ch.streams.map { stream ->
                    val old = oldStreams[stream.id] ?: oldByIdentity[channelId to M3uParser.streamIdentity(stream.url)]?.singleOrNull()
                    old?.copy(url = stream.url, channelId = channelId, headers = RuleCodec.json.encodeToString(stream.headers)) ?: StreamRow(stream.id, channelId, stream.url, playlist.id, headers = RuleCodec.json.encodeToString(stream.headers))
                }
            }
            val affected = rows.map { it.channelId }.toSet()
            val removable = if (partial) oldStreams.values.filter { it.channelId in affected }.map { it.id } else oldStreams.keys.toList()
            val deleted = removable - rows.map { it.id }.toSet()
            if (deleted.isNotEmpty()) dao.deleteStreams(deleted)
            val changed = rows.filter { it != oldStreams[it.id] }
            if (changed.isNotEmpty()) dao.saveStreams(changed)
            dao.pruneHistory(); dao.pruneFailures()
            if (!partial) dao.savePlaylist(PlaylistRow(playlist.id, playlist.name, playlist.url, playlist.enabled,
                playlist.refreshIntervalHours, System.currentTimeMillis()))
        }
    }

    /** Download new lists before entering the transaction. Other subscriptions and manual playlists are never owned here. */
    suspend fun syncUnifiedLive(sources: List<com.tvatlas.core.vod.SubscribedLiveSource>, removedUrls: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        val old = dao.playlists().first()
        val removedCount = old.filter { it.id.startsWith("unified-live:") && it.url in removedUrls }
            .sumOf { dao.sourceStreams(it.id).size }
        var stagedStreams = (dao.managedStreamCount() - removedCount).coerceAtLeast(0)
        val wanted = sources.distinctBy { it.url }.mapNotNull { source ->
            if (old.any { it.url == source.url && !it.id.startsWith("unified-live:") }) return@mapNotNull null
            val id = "unified-live:" + stableId(source.url)
            val previous = old.firstOrNull { it.id == id }
            val playlist = Playlist(id, source.name, source.url, previous?.enabled ?: true, previous?.refreshIntervalHours ?: 24, previous?.lastUpdatedAt)
            val parsed = if (previous == null) {
                val body = kotlinx.coroutines.withTimeout(10000) {
                    fetch?.invoke(source.url) ?: directClient.newCall(Request.Builder().url(source.url).build()).readCancellable { response ->
                        require(response.isSuccessful)
                        val input = requireNotNull(response.body).byteStream()
                        input.use { stream ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val count = stream.read(buffer); if (count < 0) break
                                require(output.size() + count <= 8 * 1024 * 1024); output.write(buffer, 0, count) }
                            output.toByteArray().toString(Charsets.UTF_8)
                        }
                    }
                }
                require(body.toByteArray().size <= 8 * 1024 * 1024)
                M3uParser.parse(body, id).also { parsed ->
                    require(parsed.isNotEmpty()) { "直播列表暂不可用，保留旧列表" }
                    stagedStreams = com.tvatlas.core.vod.MediaSubscription.checkedLiveBudget(parsed.size, parsed.sumOf { it.streams.size }, stagedStreams)
                }
            } else null
            playlist to parsed
        }
        db.withTransaction {
            wanted.forEach { (playlist, parsed) ->
                if (parsed != null) persist(playlist, parsed)
                else old.firstOrNull { it.id == playlist.id }?.let { previous ->
                    if (previous.name != playlist.name) dao.savePlaylist(previous.copy(name = playlist.name))
                }
            }
            val desiredIds = sources.map { "unified-live:" + stableId(it.url) }.toSet()
            old.filter { it.id.startsWith("unified-live:") && it.id !in desiredIds && it.url in removedUrls }.forEach { row ->
                dao.deleteStreams(dao.sourceStreams(row.id).map { it.id }); dao.deletePlaylist(row.id)
            }
            dao.pruneHistory(); dao.pruneFailures()
        }
    }

    suspend fun enablePlaylist(id: String, enabled: Boolean) = dao.enablePlaylist(id, enabled)
    suspend fun removePlaylist(id: String) = db.withTransaction {
        dao.deleteStreams(dao.sourceStreams(id).map { it.id }); dao.deletePlaylist(id)
        dao.pruneHistory(); dao.pruneFailures()
    }

    suspend fun refreshBuiltIn(): String {
        val playlists = library.first().playlists.filter { DefaultPlaylists.contains(it) && it.enabled }
        if (playlists.isEmpty()) return "内置订阅已停用，没有需要刷新的频道源"
        var updated = 0
        val failed = mutableListOf<String>()
        for (playlist in playlists) {
            try { refresh(playlist); updated++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed.add(playlist.name); log?.error("PLAYLIST_BUNDLE", error) }
        }
        return if (failed.isEmpty()) "TVAtlas 频道订阅已更新，线路设置与播放记忆已保留"
            else "已更新 $updated 个来源；${failed.joinToString("、")}更新失败，已保留原频道"
    }

    suspend fun saveProxy(profile: ProxyProfile) {
        RuleCodec.validateProxy(profile)
        dao.saveProxies(listOf(ProxyRow(profile.id, profile.name, profile.type.name, profile.host, profile.port, profile.enabled, profile.subscriptionId)))
    }
    suspend fun importRules(text: String, profiles: List<ProxyProfile>, fileName: String? = null): RuleConfig {
        val config = withContext(Dispatchers.Default) { RuleCodec.parse(text, profiles) }
        db.withTransaction {
            dao.saveProxies(config.proxies.map { ProxyRow(it.id, it.name, it.type.name, it.host, it.port, it.enabled) })
            dao.deactivateRuleFiles()
            val json = RuleCodec.export(config)
            dao.saveRuleFile(RuleFileRow(stableId(text), fileName?.takeIf { it.isNotBlank() }?.take(100) ?: config.name, json, System.currentTimeMillis(), true))
            dao.saveRules(RulesRow(json = json))
        }
        return config
    }
    suspend fun selectRuleFile(id: String?) {
        val profiles = library.first().profiles
        db.withTransaction {
            val row = id?.let { requireNotNull(dao.ruleFile(it)) { "规则文件不存在" } }
            val config = row?.let { RuleCodec.parse(it.json, profiles) } ?: RuleConfig()
            dao.deactivateRuleFiles()
            if (row != null) {
                dao.saveRuleFile(row.copy(active = true))
                dao.saveProxies(config.proxies.map { ProxyRow(it.id, it.name, it.type.name, it.host, it.port, it.enabled) })
            }
            dao.saveRules(RulesRow(json = RuleCodec.export(config)))
        }
    }
    suspend fun resetChannelRoutes(id: String) = db.withTransaction {
        dao.channelRoute(id, null)
        dao.resetStreamRoutes(id)
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
