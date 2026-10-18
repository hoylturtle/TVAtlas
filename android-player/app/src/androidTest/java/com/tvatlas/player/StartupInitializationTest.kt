package com.tvatlas.player

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.Playlist
import com.tvatlas.player.storage.PlayerDatabase
import com.tvatlas.player.storage.PlayerRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class StartupInitializationTest {
    @Test fun reportsLocalReadinessBeforeNetworkAndCompletesAfterAFailedSource() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PlayerDatabase::class.java).build()
        try {
            val progress = mutableListOf<Pair<Int, Int>>()
            var requested = 0
            val repository = PlayerRepository(db, OkHttpClient(), fetch = {
                assertEquals(0 to 2, progress.first())
                requested++
                if (requested == 1) error("offline fixture")
                "#EXTM3U\n#EXTINF:-1,测试台\nhttps://video.test/live.m3u8\n"
            })
            repository.initializeDefaults(listOf(Playlist("a", "A", "https://lists.test/a"), Playlist("b", "B", "https://lists.test/b"))) { done, total -> progress += done to total }
            assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
            assertEquals(2, requested)
            assertNull(db.dao().playlist("a")!!.lastUpdatedAt)
            assertNotNull(db.dao().playlist("b")!!.lastUpdatedAt)
        } finally { db.close() }
    }
}
