package com.tvatlas.player

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleCodec
import com.tvatlas.player.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RouteResetTest {
    @Test fun restoreAutoClearsEveryManualLevelButPreservesSourceAndHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PlayerDatabase::class.java).build()
        try {
            val forced = RuleCodec.json.encodeToString(RouteTarget.proxy("hk1"))
            db.dao().savePlaylist(PlaylistRow("p", "fixture", "https://fixture.test/list"))
            db.dao().saveChannel(ChannelRow("c", "翡翠台", "香港 · 综合", null, null, forced))
            db.dao().saveStreams(listOf(StreamRow("s1", "c", "https://fixture.test/1", "p", forced),
                StreamRow("s2", "c", "https://fixture.test/2", "p", forced)))
            val repository = PlayerRepository(db, OkHttpClient())
            repository.recordSuccess("c", RouteAttempt("s1", "https://fixture.test/1", RouteTarget.proxy("hk1"), "manual"))
            repository.resetChannelRoutes("c")
            assertNull(db.dao().channel("c")!!.manualRoute)
            assertTrue(db.dao().sourceStreams("p").all { it.manualRoute == null })
            assertEquals(2, repository.library.first().channels.single().streams.size)
            assertEquals("s1", db.dao().history("c")!!.streamId)
        } finally { db.close() }
    }
}
