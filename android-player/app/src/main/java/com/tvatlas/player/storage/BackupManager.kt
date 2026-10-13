package com.tvatlas.player.storage

import androidx.room.withTransaction
import com.tvatlas.core.backup.BackupCipher
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleCodec
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class ConfigurationArchive(
    val version: Int = 1, val playlists: List<PlaylistRow>, val channels: List<ChannelRow>, val streams: List<StreamRow>,
    val proxies: List<ProxyRow>, val subscriptions: List<SubscriptionRow>, val history: List<HistoryRow>,
    val failures: List<FailureRow>, val ruleFiles: List<RuleFileRow>, val rules: List<RulesRow>,
    val preferences: SavedPreferences, val credentials: Map<String, Credentials>,
)
class BackupManager(private val db: PlayerDatabase, private val vault: CredentialVault, private val settings: SettingsStore) {
    private suspend fun snapshot(): ConfigurationArchive {
        val prefs = settings.snapshot(); val secrets = withContext(Dispatchers.IO) { vault.snapshot() }
        return db.withTransaction { db.dao().let { dao -> ConfigurationArchive(
            playlists = dao.playlistsSnapshot(), channels = dao.channelsSnapshot(), streams = dao.streamsSnapshot(),
            proxies = dao.proxiesSnapshot(), subscriptions = dao.subscriptionsSnapshot(), history = dao.historySnapshot(),
            failures = dao.failuresSnapshot(), ruleFiles = dao.rulefilesSnapshot(), rules = dao.rulesSnapshot(),
            preferences = prefs, credentials = secrets) } }
    }
    suspend fun export(password: CharArray): ByteArray = withContext(Dispatchers.IO) {
        try { val archive = snapshot(); validate(archive); BackupCipher.encrypt(RuleCodec.json.encodeToString(archive).toByteArray(), password) }
        finally { password.fill('\u0000') }
    }
    suspend fun restore(bytes: ByteArray, password: CharArray) = withContext(Dispatchers.IO) {
        val archive = try { RuleCodec.json.decodeFromString<ConfigurationArchive>(BackupCipher.decrypt(bytes, password).toString(Charsets.UTF_8)) }
            finally { password.fill('\u0000') }
        validate(archive)
        val previous = snapshot()
        try {
            vault.replace(archive.credentials)
            writeDatabase(archive)
            settings.restore(archive.preferences)
        } catch (error: Exception) {
            withContext(NonCancellable) { vault.replace(previous.credentials); writeDatabase(previous); settings.restore(previous.preferences) }
            throw error
        }
    }
    private suspend fun writeDatabase(value: ConfigurationArchive) = db.withTransaction {
        val dao = db.dao()
        dao.clearFailures(); dao.clearHistory(); dao.clearStreams(); dao.clearChannels(); dao.clearPlaylists()
        dao.clearProxies(); dao.clearSubscriptions(); dao.clearRulefiles(); dao.clearRules()
        value.playlists.forEach { dao.savePlaylist(it) }; value.channels.forEach { dao.saveChannel(it) }; dao.saveStreams(value.streams)
        dao.saveProxies(value.proxies); value.subscriptions.forEach { dao.saveSubscription(it) }
        value.history.forEach { dao.saveHistory(it) }; value.failures.forEach { dao.saveFailure(it) }
        value.ruleFiles.forEach { dao.saveRuleFile(it) }; value.rules.forEach { dao.saveRules(it) }
    }
    companion object {
        fun validate(value: ConfigurationArchive) {
            require(value.version == 1 && value.streams.size <= 50000 && value.channels.size <= 20000 && value.proxies.size <= 2000) { "备份格式或数量无效" }
            require(value.ruleFiles.count { it.active } <= 1 && value.rules.size <= 1)
            require(value.preferences.options.refreshHours in 1..168 && value.preferences.options.limitMiB in 0..1048576)
            require(value.preferences.options.epgUrl.isBlank() || httpUri(value.preferences.options.epgUrl) != null)
            val playlists = value.playlists.map { it.id }.toSet(); val channels = value.channels.map { it.id }.toSet()
            require(playlists.size == value.playlists.size && channels.size == value.channels.size && value.streams.map { it.id }.distinct().size == value.streams.size)
            value.playlists.forEach { require(httpUri(it.url) != null && it.refreshIntervalHours in 1..168) }
            value.streams.forEach { require(it.channelId in channels && it.sourcePlaylistId in playlists && httpUri(it.url) != null) }
            val profiles = value.proxies.map { ProxyProfile(it.id, it.name, ProxyType.valueOf(it.type), it.host, it.port, it.enabled, it.subscriptionId) }
            profiles.forEach { RuleCodec.validateProxy(it) }
            require(profiles.map { it.id }.distinct().size == profiles.size)
            fun validateRules(json: String) {
                val config = RuleCodec.json.decodeFromString<com.tvatlas.core.routing.RuleConfig>(json)
                val referenced = (listOf(config.defaultRoute) + config.rules.map { it.route }).flatMap { listOfNotNull(it.profile) + it.`try`.filter { id -> id != "DIRECT" } }.distinct()
                val deleted = referenced.filter { id -> profiles.none { it.id == id } }.map { id -> ProxyProfile(id, "已移除", ProxyType.HTTP, "removed.invalid", 1, false) }
                deleted.forEach { RuleCodec.validateProxy(it) }; RuleCodec.validate(config, profiles + deleted)
            }
            value.ruleFiles.forEach { validateRules(it.json) }; value.rules.forEach { validateRules(it.json) }
            val allowed = profiles.flatMap { listOf(it.id, "node:${it.id}") }.toSet() + value.subscriptions.map { "subscription:${it.id}:url" }
            require(value.credentials.keys.all { it in allowed }) { "备份凭证关联无效" }
            value.channels.forEach { it.manualRoute?.let { route -> RuleCodec.json.decodeFromString<RouteTarget>(route) } }
            value.streams.forEach { it.manualRoute?.let { route -> RuleCodec.json.decodeFromString<RouteTarget>(route) } }
        }
    }
}
