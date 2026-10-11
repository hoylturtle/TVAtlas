package com.tvatlas.player

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.*
import com.tvatlas.player.source.MyTvSuperSource
import com.tvatlas.player.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultPlaylistsTest {
    @Test fun seedsOfficialOfflineAndRetriesFailedSubscriptionWithoutDuplicates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).build()
        val server = MockWebServer().apply { start() }
        try {
            val remoteUrl = server.url("/channels.m3u").toString()
            val remote = Playlist(stableId(remoteUrl), "频道订阅", remoteUrl)
            val official = DefaultPlaylists.entries.first()
            val defaults = listOf(official, remote)
            val repo = PlayerRepository(db, OkHttpClient())
            server.enqueue(MockResponse().setResponseCode(503))
            repo.initializeDefaults(defaults)
            assertEquals(2, db.dao().playlists().first().size)
            assertNull(db.dao().playlist(remote.id)!!.lastUpdatedAt)
            assertEquals(MyTvSuperSource.PAGE, db.dao().sourceStreams(official.id).single().url)
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1 group-title=\"新闻\",测试频道\nhttps://example.com/live.m3u8\n"))
            repo.initializeDefaults(defaults)
            assertNotNull(db.dao().playlist(remote.id)!!.lastUpdatedAt)
            repo.initializeDefaults(defaults)
            assertEquals(2, server.requestCount)
            assertEquals(2, db.dao().playlists().first().size)
            assertEquals(1, db.dao().sourceStreams(remote.id).size)
        } finally { db.close(); server.shutdown() }
    }

    @Test fun preservesExistingPlaylistAndManualRoutesWhenAddingDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).build()
        try {
            val official = DefaultPlaylists.entries.first()
            val repo = PlayerRepository(db, OkHttpClient())
            repo.refresh(official)
            val stream = db.dao().sourceStreams(official.id).single()
            db.dao().channelRoute(stream.channelId, "saved-channel-route")
            db.dao().streamRoute(stream.id, "saved-stream-route")
            val custom = db.dao().playlist(official.id)!!.copy(name = "我的官方线路", enabled = false, refreshIntervalHours = 72)
            db.dao().savePlaylist(custom)
            val remote = DefaultPlaylists.entries.last()
            val remoteDisabled = PlaylistRow(remote.id, "保留名称", remote.url, enabled = false)
            db.dao().savePlaylist(remoteDisabled)
            repo.initializeDefaults()
            assertEquals(custom, db.dao().playlist(official.id))
            assertEquals(remoteDisabled, db.dao().playlist(remote.id))
            assertEquals("saved-channel-route", db.dao().channel(stream.channelId)!!.manualRoute)
            assertEquals("saved-stream-route", db.dao().stream(stream.id)!!.manualRoute)
        } finally { db.close() }
    }
}
