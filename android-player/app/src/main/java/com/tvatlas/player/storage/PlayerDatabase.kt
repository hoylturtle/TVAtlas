package com.tvatlas.player.storage

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@kotlinx.serialization.Serializable
@Entity(tableName = "playlists") data class PlaylistRow(
    @PrimaryKey val id: String, val name: String, val url: String,
    val enabled: Boolean = true, val refreshIntervalHours: Int = 24, val lastUpdatedAt: Long? = null,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "channels") data class ChannelRow(
    @PrimaryKey val id: String, val name: String, val channelGroup: String,
    val logo: String?, val tvgId: String?, val manualRoute: String? = null,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "streams", indices = [Index("channelId"), Index("sourcePlaylistId")]) data class StreamRow(
    @PrimaryKey val id: String, val channelId: String, val url: String, val sourcePlaylistId: String,
    val manualRoute: String? = null, val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null, val failureCount: Int = 0,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "proxies") data class ProxyRow(
    @PrimaryKey val id: String, val name: String, val type: String, val host: String,
    val port: Int, val enabled: Boolean, val subscriptionId: String? = null,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "subscriptions") data class SubscriptionRow(
    @PrimaryKey val id: String, val name: String, val lastUpdatedAt: Long, val nodeCount: Int,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "history") data class HistoryRow(
    @PrimaryKey val channelId: String, val streamId: String, val target: String, val at: Long,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "failures", primaryKeys = ["channelId", "streamId", "target"]) data class FailureRow(
    val channelId: String, val streamId: String, val target: String, val reason: String, val at: Long,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "rule_files") data class RuleFileRow(
    @PrimaryKey val id: String, val name: String, val json: String, val importedAt: Long, val active: Boolean,
)
@kotlinx.serialization.Serializable
@Entity(tableName = "rules") data class RulesRow(@PrimaryKey val id: Int = 1, val json: String)

@Dao interface PlayerDao {
    @Query("SELECT * FROM rules") suspend fun rulesSnapshot(): List<RulesRow>
    @Query("DELETE FROM rules") suspend fun clearRules()
    @Query("SELECT * FROM rule_files") suspend fun rulefilesSnapshot(): List<RuleFileRow>
    @Query("DELETE FROM rule_files") suspend fun clearRulefiles()
    @Query("SELECT * FROM failures") suspend fun failuresSnapshot(): List<FailureRow>
    @Query("DELETE FROM failures") suspend fun clearFailures()
    @Query("SELECT * FROM history") suspend fun historySnapshot(): List<HistoryRow>
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("SELECT * FROM subscriptions") suspend fun subscriptionsSnapshot(): List<SubscriptionRow>
    @Query("DELETE FROM subscriptions") suspend fun clearSubscriptions()
    @Query("SELECT * FROM proxies") suspend fun proxiesSnapshot(): List<ProxyRow>
    @Query("DELETE FROM proxies") suspend fun clearProxies()
    @Query("SELECT * FROM streams") suspend fun streamsSnapshot(): List<StreamRow>
    @Query("DELETE FROM streams") suspend fun clearStreams()
    @Query("SELECT * FROM channels") suspend fun channelsSnapshot(): List<ChannelRow>
    @Query("DELETE FROM channels") suspend fun clearChannels()
    @Query("SELECT * FROM playlists") suspend fun playlistsSnapshot(): List<PlaylistRow>
    @Query("DELETE FROM playlists") suspend fun clearPlaylists()
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT 100") fun recent(): Flow<List<HistoryRow>>

    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun playlist(id: String): PlaylistRow?
    @Query("SELECT * FROM playlists ORDER BY name") fun playlists(): Flow<List<PlaylistRow>>
    @Query("SELECT * FROM channels ORDER BY channelGroup, name") fun channels(): Flow<List<ChannelRow>>
    @Query("SELECT * FROM streams ORDER BY sourcePlaylistId, id") fun streams(): Flow<List<StreamRow>>
    @Query("SELECT * FROM proxies ORDER BY id") fun proxies(): Flow<List<ProxyRow>>
    @Query("SELECT * FROM rule_files ORDER BY importedAt DESC, id") fun ruleFiles(): Flow<List<RuleFileRow>>
    @Query("SELECT * FROM rule_files WHERE id = :id") suspend fun ruleFile(id: String): RuleFileRow?
    @Query("UPDATE rule_files SET active = 0") suspend fun deactivateRuleFiles()
    @Upsert suspend fun saveRuleFile(row: RuleFileRow)
    @Query("SELECT * FROM rules WHERE id = 1") fun rules(): Flow<RulesRow?>
    @Query("SELECT * FROM subscriptions ORDER BY name") fun subscriptions(): Flow<List<SubscriptionRow>>
    @Query("SELECT * FROM proxies WHERE subscriptionId = :id") suspend fun subscriptionNodes(id: String): List<ProxyRow>
    @Query("SELECT * FROM proxies WHERE type = 'MIHOMO' AND enabled = 1") suspend fun coreNodes(): List<ProxyRow>
    @Query("SELECT * FROM subscriptions WHERE id = :id") suspend fun subscription(id: String): SubscriptionRow?
    @Upsert suspend fun saveSubscription(row: SubscriptionRow)
    @Query("DELETE FROM subscriptions WHERE id = :id") suspend fun deleteSubscription(id: String)
    @Query("DELETE FROM proxies WHERE id IN (:ids)") suspend fun deleteProxies(ids: List<String>)
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: String): ChannelRow?
    @Query("SELECT * FROM streams WHERE sourcePlaylistId = :id") suspend fun sourceStreams(id: String): List<StreamRow>
    @Query("SELECT * FROM streams WHERE id = :id") suspend fun stream(id: String): StreamRow?
    @Query("SELECT * FROM history WHERE channelId = :channelId") suspend fun history(channelId: String): HistoryRow?
    @Upsert suspend fun savePlaylist(row: PlaylistRow)
    @Upsert suspend fun saveChannel(row: ChannelRow)
    @Upsert suspend fun saveStreams(rows: List<StreamRow>)
    @Upsert suspend fun saveProxies(rows: List<ProxyRow>)
    @Upsert suspend fun saveRules(row: RulesRow)
    @Upsert suspend fun saveHistory(row: HistoryRow)
    @Upsert suspend fun saveFailure(row: FailureRow)
    @Query("DELETE FROM streams WHERE id IN (:ids)") suspend fun deleteStreams(ids: List<String>)
    @Query("DELETE FROM history WHERE streamId NOT IN (SELECT id FROM streams)") suspend fun pruneHistory()
    @Query("DELETE FROM failures WHERE streamId NOT IN (SELECT id FROM streams)") suspend fun pruneFailures()
    @Query("UPDATE streams SET manualRoute = NULL WHERE channelId = :id") suspend fun resetStreamRoutes(id: String)
    @Query("UPDATE channels SET manualRoute = :route WHERE id = :id") suspend fun channelRoute(id: String, route: String?)
    @Query("UPDATE streams SET manualRoute = :route WHERE id = :id") suspend fun streamRoute(id: String, route: String?)
    @Query("UPDATE streams SET lastSuccessAt = :at WHERE id = :id") suspend fun success(id: String, at: Long)
    @Query("UPDATE streams SET lastFailureAt = :at, failureCount = failureCount + 1 WHERE id = :id") suspend fun failure(id: String, at: Long)
}

@Database(entities = [PlaylistRow::class, ChannelRow::class, StreamRow::class, ProxyRow::class,
    HistoryRow::class, FailureRow::class, RulesRow::class, SubscriptionRow::class, RuleFileRow::class], version = 3, exportSchema = true)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun dao(): PlayerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE proxies ADD COLUMN subscriptionId TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS subscriptions (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, lastUpdatedAt INTEGER NOT NULL, nodeCount INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS rule_files (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, json TEXT NOT NULL, importedAt INTEGER NOT NULL, active INTEGER NOT NULL)")
                db.execSQL("INSERT INTO rule_files (id,name,json,importedAt,active) SELECT 'legacy','原有自定义规则',json,0,1 FROM rules WHERE id=1")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, PlayerDatabase::class.java, "tvatlas.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
