package com.tvatlas.player.storage

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists") data class PlaylistRow(
    @PrimaryKey val id: String, val name: String, val url: String,
    val enabled: Boolean = true, val refreshIntervalHours: Int = 24, val lastUpdatedAt: Long? = null,
)
@Entity(tableName = "channels") data class ChannelRow(
    @PrimaryKey val id: String, val name: String, val channelGroup: String,
    val logo: String?, val tvgId: String?, val manualRoute: String? = null,
)
@Entity(tableName = "streams", indices = [Index("channelId"), Index("sourcePlaylistId")]) data class StreamRow(
    @PrimaryKey val id: String, val channelId: String, val url: String, val sourcePlaylistId: String,
    val manualRoute: String? = null, val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null, val failureCount: Int = 0,
)
@Entity(tableName = "proxies") data class ProxyRow(
    @PrimaryKey val id: String, val name: String, val type: String, val host: String,
    val port: Int, val enabled: Boolean,
)
@Entity(tableName = "history") data class HistoryRow(
    @PrimaryKey val channelId: String, val streamId: String, val target: String, val at: Long,
)
@Entity(tableName = "failures", primaryKeys = ["channelId", "streamId", "target"]) data class FailureRow(
    val channelId: String, val streamId: String, val target: String, val reason: String, val at: Long,
)
@Entity(tableName = "rules") data class RulesRow(@PrimaryKey val id: Int = 1, val json: String)

@Dao interface PlayerDao {
    @Query("SELECT * FROM playlists ORDER BY name") fun playlists(): Flow<List<PlaylistRow>>
    @Query("SELECT * FROM channels ORDER BY channelGroup, name") fun channels(): Flow<List<ChannelRow>>
    @Query("SELECT * FROM streams ORDER BY sourcePlaylistId, id") fun streams(): Flow<List<StreamRow>>
    @Query("SELECT * FROM proxies ORDER BY id") fun proxies(): Flow<List<ProxyRow>>
    @Query("SELECT * FROM rules WHERE id = 1") fun rules(): Flow<RulesRow?>
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
    @Query("UPDATE channels SET manualRoute = :route WHERE id = :id") suspend fun channelRoute(id: String, route: String?)
    @Query("UPDATE streams SET manualRoute = :route WHERE id = :id") suspend fun streamRoute(id: String, route: String?)
    @Query("UPDATE streams SET lastSuccessAt = :at WHERE id = :id") suspend fun success(id: String, at: Long)
    @Query("UPDATE streams SET lastFailureAt = :at, failureCount = failureCount + 1 WHERE id = :id") suspend fun failure(id: String, at: Long)
}

@Database(entities = [PlaylistRow::class, ChannelRow::class, StreamRow::class, ProxyRow::class,
    HistoryRow::class, FailureRow::class, RulesRow::class], version = 1, exportSchema = true)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun dao(): PlayerDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, PlayerDatabase::class.java, "tvatlas.db").build()
    }
}
