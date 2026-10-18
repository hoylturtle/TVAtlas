package com.tvatlas.player

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.stableId
import com.tvatlas.core.vod.SubscribedLiveSource
import com.tvatlas.player.storage.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class UnifiedLiveSubscriptionTest {
    @Test fun stagesNewListsBeforeMutationAndPreservesManualAndDisabledPlaylists() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PlayerDatabase::class.java).build()
        try {
            val manual = "https://lists.test/manual.m3u"
            val source = "https://lists.test/owned.m3u"
            db.dao().savePlaylist(PlaylistRow("manual", "自己添加", manual))
            var oversized = false
            val repository = PlayerRepository(db, OkHttpClient(), fetch = { url ->
                require(!url.contains("broken"))
                if (oversized) "#EXTM3U\n" + (1..2001).joinToString("\n") { "#EXTINF:-1 group-title=\"中国大陆 · 卫视\",频道$it\nhttps://video.test/$it.m3u8" }
                else "#EXTM3U\n#EXTINF:-1 group-title=\"中国大陆 · 卫视\",测试台\nhttps://video.test/live.m3u8\n"
            })
            repository.syncUnifiedLive(listOf(SubscribedLiveSource("订阅项", source), SubscribedLiveSource("同地址", manual)))
            assertEquals(2, db.dao().playlists().first().size)
            val id = "unified-live:" + stableId(source)
            db.dao().enablePlaylist(id, false)
            repository.syncUnifiedLive(listOf(SubscribedLiveSource("改名", source)))
            assertFalse(db.dao().playlist(id)!!.enabled)
            assertEquals("改名", db.dao().playlist(id)!!.name)
            oversized = true
            try {
                repository.refresh(com.tvatlas.core.model.Playlist(id, "改名", source, enabled = false))
                fail("Automatic or manual refresh must obey the same bounds as import")
            } catch (_: com.tvatlas.core.vod.MediaSubscriptionLimitException) { }
            assertEquals(1, db.dao().sourceStreams(id).size)
            oversized = false
            try {
                repository.syncUnifiedLive(listOf(SubscribedLiveSource("故障", "https://lists.test/broken.m3u")), setOf(source))
                fail("Broken replacement must not remove old playlist")
            } catch (_: IllegalArgumentException) { }
            assertNotNull(db.dao().playlist(id))
            repository.syncUnifiedLive(emptyList(), setOf(source, manual))
            assertNull(db.dao().playlist(id)); assertNotNull(db.dao().playlist("manual"))
        } finally { db.close() }
    }
}
