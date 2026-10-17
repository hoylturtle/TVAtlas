package com.tvatlas.player

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tvatlas.core.model.Channel
import com.tvatlas.core.model.Stream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A late playback request must not resurrect live playback after the page was left. */
@RunWith(AndroidJUnit4::class)
class LivePageLifecycleTest {
    @Test fun leavingLiveClearsPreparationAndRejectsLateRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            try {
                val app = instrumentation.targetContext.applicationContext as Application
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java]
                val first = Channel("lifecycle-test-a", "Lifecycle fixture A", "测试", streams = listOf(Stream("a", "https://example.com/a.m3u8", "test")))
                val late = first.copy(id = "lifecycle-test-b", name = "Lifecycle fixture B")
                model.playback.preparing(first, null)
                assertTrue(model.playback.status.value.loading)
                model.setLivePageActive(false)
                assertFalse(model.playback.status.value.loading)
                assertNull(model.playback.player.value)
                model.play(late)
                assertEquals(first.id, model.playback.status.value.channelId)
                assertFalse(model.playback.status.value.loading)
                model.setLivePageActive(true)
                // Returning does not automatically start a channel or consume traffic.
                assertNull(model.playback.player.value)
                assertFalse(model.playback.status.value.loading)
            } finally { store.clear() }
        }
    }
}
