package com.tvatlas.player.playback

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.Channel
import com.tvatlas.player.proxy.ProxyClientPool
import com.tvatlas.player.storage.PlayerDatabase
import com.tvatlas.player.storage.PlayerRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ReplayStateTest {
    @Test fun stopDuringPreparationPreservesChannelAndSelectedLineForReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlayerDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val pool = ProxyClientPool({ null })
        try {
            val coordinator = PlaybackCoordinator(context, PlayerRepository(db, OkHttpClient()), pool, scope)
            val channel = Channel("jade", "翡翠台", "香港 · 综合", streams = emptyList())
            coordinator.preparing(channel, "official-line")
            assertTrue(coordinator.status.value.loading)
            coordinator.stop()
            val stopped = coordinator.status.value
            assertEquals("jade", stopped.channelId)
            assertEquals("official-line", stopped.selectedStreamId)
            assertEquals("已停止播放", stopped.message)
            assertFalse(stopped.loading)
            assertNull(coordinator.player.value)
            coordinator.preparing(channel)
            assertNull(coordinator.status.value.selectedStreamId)
            assertTrue(coordinator.status.value.loading)
            coordinator.stop()
        } finally { scope.cancel(); pool.close(); db.close() }
    }
}
