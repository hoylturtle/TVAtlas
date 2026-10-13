package com.tvatlas.player

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.*
import com.tvatlas.player.source.MyTvSuperSource
import com.tvatlas.player.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class SourcePolicyTest {
    @Test fun hongKongExcludesThirdPartyWhileOtherRegionsRemainAvailable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PlayerDatabase::class.java).build()
        try {
            val repo = PlayerRepository(db, OkHttpClient(), fetch = {
                "#EXTM3U\n#EXTINF:-1 tvg-id=\"Jade.hk\" group-title=\"香港\",翡翠台\nhttps://thirdparty.test/jade\n#EXTINF:-1 group-title=\"中国大陆\",大陆新闻\nhttps://thirdparty.test/news\n"
            })
            repo.refresh(Playlist("custom", "fixture", "https://example.test/source"))
            repo.refresh(DefaultPlaylists.entries.first())
            val library = repo.library.first()
            assertEquals(2, library.channels.size)
            assertEquals(listOf(MyTvSuperSource.PAGE), library.channels.single { it.isHongKong() }.streams.map { it.url })
            assertEquals("https://thirdparty.test/news", library.channels.single { !it.isHongKong() }.streams.single().url)
        } finally { db.close() }
    }
    @Test fun rotatingLegacySignedUrlKeepsRoutesHistoryAndOriginalChannelId() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PlayerDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.savePlaylist(PlaylistRow("p", "fixture", "https://example.test/source"))
            dao.saveChannel(ChannelRow("legacy-channel", "fixture", "大陆", null, null))
            dao.saveStreams(listOf(StreamRow("legacy-line", "legacy-channel", "https://example.test/live?channel=1&token=old", "p", manualRoute = """{"type":"DIRECT"}""")))
            dao.saveHistory(HistoryRow("legacy-channel", "legacy-line", """{"type":"DIRECT"}""", 1))
            val repo = PlayerRepository(db, OkHttpClient(), fetch = { "#EXTINF:-1 group-title=\"大陆\",fixture\nhttps://example.test/live?token=new&channel=1\n" })
            repo.refresh(Playlist("p", "fixture", "https://example.test/source"))
            val channel = repo.library.first().channels.single()
            assertEquals("legacy-channel", channel.id)
            assertEquals("legacy-line", channel.streams.single().id)
            assertEquals(RouteTarget.DIRECT, channel.streams.single().manualRoute)
            assertTrue(channel.streams.single().url.contains("token=new"))
            assertNotNull(dao.history("legacy-channel"))
        } finally { db.close() }
    }
    @Test fun deletingOneSourcePreservesSharedChannelsOtherOfficialLines() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PlayerDatabase::class.java).build()
        try {
            val repo = PlayerRepository(db, OkHttpClient())
            repo.refresh(Playlist("a", "A", MyTvSuperSource.PAGE))
            repo.refresh(Playlist("b", "B", MyTvSuperSource.PAGE))
            repo.removePlaylist("a")
            assertNull(db.dao().playlist("a"))
            assertEquals("b", repo.library.first().channels.single().streams.single().sourcePlaylistId)
            repo.enablePlaylist("b", false)
            assertTrue(repo.library.first().channels.isEmpty())
            repo.enablePlaylist("b", true)
            assertEquals(1, repo.library.first().channels.size)
        } finally { db.close() }
    }
}
