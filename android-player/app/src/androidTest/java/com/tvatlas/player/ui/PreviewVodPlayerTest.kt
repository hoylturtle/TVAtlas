package com.tvatlas.player.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.vod.*
import com.tvatlas.player.vod.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewVodPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()

    @Test fun nativePlayerSeeksResumesAndHonoursNetworkPolicy() {
        compose.setContent { AtlasTheme {} }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("vod-fixture.mp4").use { it.readBytes() }
        val server = MockWebServer()
        repeat(8) { server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))) }
        server.start()
        val records = ConcurrentHashMap<String, VodBookmark>()
        val storage = object : VodProgressStorage {
            override fun load(key: String) = records[key]
            override fun save(key: String, bookmark: VodBookmark) { records[key] = bookmark }
        }
        val allowed = AtomicBoolean(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: VodPlaybackController
        var created = false
        val item = VodItem.direct(server.url("/movie.mp4").toString(), "点播测试")
        try {
            compose.runOnIdle {
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, allowed::get)
                created = true
                controller.open(item)
            }
            compose.waitUntil(20000) { controller.state.value.ready }
            compose.runOnIdle {
                assertTrue(controller.state.value.player!!.isCurrentMediaItemSeekable)
                controller.pause()
                controller.seekBy(30000)
                controller.pause()
                controller.close()
            }
            compose.waitUntil(5000) { (records[item.key]?.positionMs ?: 0) >= 30000 }
            compose.runOnIdle { controller.open(item) }
            compose.waitUntil(20000) { controller.state.value.ready }
            compose.runOnIdle {
                assertTrue(controller.state.value.player!!.currentPosition >= 30000)
                controller.pause()
                allowed.set(false)
                controller.state.value.player!!.play()
            }
            compose.waitUntil(5000) { controller.state.value.error != null }
            compose.runOnIdle {
                assertFalse(controller.state.value.player!!.playWhenReady)
                assertNotNull(controller.state.value.error)
            }
        } finally {
            compose.runOnIdle { if (created) controller.close() }
            scope.cancel(); server.shutdown()
        }
    }

    @Test fun pauseDuringResumeLookupDoesNotStartPlaybackLater() {
        compose.setContent { AtlasTheme {} }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? {
                started.set(true)
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                completed.set(true)
                return null
            }
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: VodPlaybackController
        compose.runOnIdle {
            controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
            controller.open(VodItem.direct("https://media.example/movie.mp4"))
        }
        try {
            compose.waitUntil(5000) { started.get() }
            compose.runOnIdle { controller.pause() }
            gate.countDown()
            compose.waitUntil(5000) { completed.get() }
            compose.runOnIdle {
                assertNull(controller.state.value.player)
                assertEquals("已暂停，点击播放继续", controller.state.value.error)
            }
        } finally { gate.countDown(); compose.runOnIdle { controller.close() }; scope.cancel() }
    }

    @Test fun webpageResponseShowsSafePlaybackError() {
        compose.setContent { AtlasTheme {} }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        repeat(5) { server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login required</html>")) }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? = null
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        lateinit var controller: VodPlaybackController
        val item = VodItem.direct(server.url("/page?token=private-fixture").toString())
        compose.runOnIdle {
            controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
            controller.open(item)
        }
        try {
            compose.waitUntil(20000) { controller.state.value.error != null }
            val error = controller.state.value.error!!
            assertTrue(error.contains("可识别的视频"))
            assertFalse(error.contains("private-fixture"))
        } finally { compose.runOnIdle { controller.close() }; scope.cancel(); server.shutdown() }
    }

    @Test fun encryptedResumeStorageKeepsDamagedRecordsForRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("vod_progress", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val item = VodItem.direct("https://media.example/movie.mp4?token=private", "私密标题")
            val bookmark = VodBookmark(30000, 120000)
            VodProgressStore(context).save(item.key, bookmark)
            assertEquals(bookmark, VodProgressStore(context).load(item.key))
            val raw = prefs.all.values.joinToString()
            assertFalse(raw.contains("private")); assertFalse(raw.contains("私密标题"))
            prefs.edit().putString("positions", "damaged").commit()
            assertThrows(Exception::class.java) { VodProgressStore(context).save(item.key, bookmark) }
            assertEquals("damaged", prefs.getString("positions", null))
        } finally { prefs.edit().clear().commit() }
    }
}
