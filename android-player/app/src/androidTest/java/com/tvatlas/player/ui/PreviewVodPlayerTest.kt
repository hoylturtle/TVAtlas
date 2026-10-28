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

    @Test fun dialogRemoteWorksWhenAnotherViewOwnsFocusAndPreservesVisibleMenuActions() {
        compose.setContent { AtlasTheme {} }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val seeks = java.util.concurrent.atomic.AtomicLong(0)
        val clicks = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var dialog: android.app.Dialog
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        lateinit var view: VodPlayerView
        lateinit var other: android.widget.Button
        var created = false
        try {
            compose.runOnIdle {
                player = androidx.media3.exoplayer.ExoPlayer.Builder(compose.activity).build()
                dialog = android.app.Dialog(compose.activity)
                view = VodPlayerView(compose.activity) { seeks.addAndGet(it) }.apply {
                    this.player = player
                    controllerShowTimeoutMs = 0
                }
                other = android.widget.Button(compose.activity).apply {
                    text = "Other control"; isFocusableInTouchMode = true
                    setOnClickListener { clicks.incrementAndGet() }
                }
                created = true
                dialog.setContentView(android.widget.LinearLayout(compose.activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(view, android.widget.LinearLayout.LayoutParams(600, 300))
                    addView(other)
                })
                dialog.show()
                val window = dialog.window!!
                window.callback = VodPlaybackWindowCallback(window.callback, { view })
                view.hideController()
                assertTrue(other.requestFocus())
            }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            compose.runOnIdle { assertEquals(-10_000L, seeks.get()) }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.runOnIdle { assertEquals(0L, seeks.get()) }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5000) { view.isControllerFullyVisible }
            compose.runOnIdle { assertTrue(other.requestFocus()) }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            compose.runOnIdle { assertEquals(1, clicks.get()); assertEquals(0L, seeks.get()) }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.runOnIdle { assertFalse(dialog.isShowing) }
        } finally {
            compose.runOnIdle {
                if (created) { dialog.dismiss(); view.player = null; player.release() }
            }
        }
    }

    @Test fun addonHttpStreamPlaysThroughExistingNativePlayer() {
        compose.setContent { AtlasTheme {} }
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("vod-fixture.mp4").use { it.readBytes() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? = null
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        val server = MockWebServer()
        var created = false
        lateinit var controller: VodPlaybackController
        try {
            server.start()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/stream/") == true -> MockResponse().setBody("""{"streams":[{"name":"Test HTTP","url":"${server.url("/movie.mp4")}"}]}""")
                    request.path == "/movie.mp4" -> MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val manifest = com.tvatlas.core.vod.stremio.StremioProtocol.manifest("""{"id":"test","name":"Test","resources":["stream"],"types":["movie"],"catalogs":[]}""")
            val entry = com.tvatlas.player.vod.stremio.AddonEntry(server.url("/manifest.json").toString(), manifest)
            val episode = runBlocking { com.tvatlas.player.vod.stremio.StremioAddonClient(OkHttpClient()).streams(entry, "movie", "tt1234567", "正片").episodes.single() }
            compose.runOnIdle {
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
                created = true
                controller.open(VodItem.episode(episode.url, "插件视频", "addon-test", "test:line", entry.source))
            }
            compose.waitUntil(20000) { controller.state.value.ready }
            compose.runOnIdle { assertTrue(controller.state.value.seekable); assertTrue(controller.state.value.canBlockSource); controller.seekBy(1000) }
        } finally {
            compose.runOnIdle { if (created) controller.close(); scope.cancel() }
            server.shutdown()
        }
    }

    @Test fun providerBlockSurvivesNewUrlsAndCanBeCleared() {
        compose.setContent { AtlasTheme {} }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? = null
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        lateinit var controller: VodPlaybackController
        val bad = VodItem.episode("https://example.invalid/one.mp4", "测试", "episode1", "source:line", "provider")
        val changed = VodItem.episode("https://example.invalid/new-token.mp4", "测试", "episode2", "otherline", "provider")
        val alternative = VodItem.episode("https://example.invalid/good.mp4", "测试", "episode1", "other:line", "other")
        try {
            compose.runOnIdle {
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
                controller.clearAdvertisementReports()
                controller.open(bad, alternatives = listOf(alternative))
                controller.blockCurrentProvider()
                assertEquals(alternative, controller.state.value.item)
                controller.close()
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
                assertTrue(controller.reported(changed)); assertFalse(controller.reported(alternative))
                controller.clearAdvertisementReports(); assertFalse(controller.reported(changed))
            }
        } finally { compose.runOnIdle { controller.clearAdvertisementReports(); controller.close(); scope.cancel() } }
    }

    @Test fun reportedAdvertisementIsRememberedAcrossControllersAndCanBeCleared() {
        compose.setContent { AtlasTheme {} }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? = null
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        val bad = VodItem.direct("https://example.invalid/advertisement-test.mp4", "测试")
        val good = VodItem.direct("https://example.invalid/alternative-test.mp4", "测试")
        lateinit var controller: VodPlaybackController
        try {
            compose.runOnIdle {
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
                controller.clearAdvertisementReports()
                controller.open(bad, alternatives = listOf(good))
                controller.reportAdvertisement()
                assertEquals(good, controller.state.value.item)
                controller.close()
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
                controller.open(bad)
                assertNotNull(controller.state.value.error)
                assertNull(controller.state.value.player)
                controller.clearAdvertisementReports()
                controller.open(bad)
                assertNull(controller.state.value.error)
            }
        } finally {
            compose.runOnIdle { controller.clearAdvertisementReports(); controller.close(); scope.cancel() }
        }
    }

    @Test fun naturalEndCanAdvanceButExplicitCloseNeverAdvances() {
        compose.setContent { AtlasTheme {} }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("vod-fixture.mp4").use { it.readBytes() }
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ended = java.util.concurrent.atomic.AtomicInteger(0)
        val closed = java.util.concurrent.atomic.AtomicInteger(0)
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? = null
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        lateinit var controller: VodPlaybackController
        var created = false
        val first = VodItem.episode(server.url("/one.mp4").toString(), "第1集", "series:episode:1")
        val next = VodItem.episode(server.url("/two.mp4").toString(), "第2集", "series:episode:2")
        try {
            compose.runOnIdle {
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true },
                    onEnded = { if (ended.incrementAndGet() == 1) controller.open(next) }, onClosed = { closed.incrementAndGet() })
                created = true
                controller.open(first)
            }
            compose.waitUntil(20000) { controller.state.value.ready }
            compose.runOnIdle { controller.state.value.player!!.let { it.seekTo((it.duration - 300).coerceAtLeast(0)); it.play() } }
            compose.waitUntil(20000) { controller.state.value.item?.key == next.key && controller.state.value.ready }
            compose.runOnIdle { assertEquals(0, closed.get()); controller.close() }
            compose.waitUntil(5000) { controller.state.value.item == null }
            compose.runOnIdle { assertEquals(1, ended.get()); assertEquals(1, closed.get()) }
        } finally {
            compose.runOnIdle { if (created) controller.close(); scope.cancel() }
            server.shutdown()
        }
    }

    @Test fun failedMediaAutomaticallyFallsBackToAnotherLine() {
        compose.setContent { AtlasTheme {} }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("vod-fixture.mp4").use { it.readBytes() }
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/denied")) MockResponse().setResponseCode(403)
                else MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? = null
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        lateinit var controller: VodPlaybackController
        val denied = VodItem.direct(server.url("/denied.mp4").toString(), "第1集")
        val good = VodItem.direct(server.url("/good.mp4").toString(), "第1集")
        try {
            compose.runOnIdle {
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { true })
                controller.open(denied, alternatives = listOf(good))
            }
            compose.waitUntil(20000) { controller.state.value.ready && controller.state.value.item == good }
            compose.runOnIdle { assertNull(controller.state.value.error) }
        } finally {
            compose.runOnIdle { controller.close() }
            scope.cancel(); server.shutdown()
        }
    }

    @Test fun localContentPlaysOfflineAndManualSkipCanBeUndone() {
        compose.setContent { AtlasTheme {} }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = java.io.File(context.cacheDir, "updates/local-playback-fixture.mp4").apply {
            parentFile!!.mkdirs()
            instrumentation.context.assets.open("vod-fixture.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val item = VodItem.localDocument(uri.toString(), "离线视频")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: VodPlaybackController
        var created = false
        val storage = object : VodProgressStorage {
            override fun load(key: String): VodBookmark? = null
            override fun save(key: String, bookmark: VodBookmark) {}
        }
        try {
            compose.runOnIdle {
                controller = VodPlaybackController(context, scope, OkHttpClient(), { storage }, { false })
                created = true; controller.open(item, label = "本地文件")
            }
            compose.waitUntil(20000) { controller.state.value.ready }
            compose.runOnIdle {
                assertTrue(controller.state.value.seekable)
                controller.state.value.skipRanges.toList().forEach(controller::removeSkip)
                controller.saveSkip(1000, 5000)
                controller.state.value.player!!.seekTo(2000)
                controller.state.value.player!!.play()
            }
            compose.waitUntil(10000) { controller.state.value.undoSkipPosition != null }
            compose.runOnIdle {
                assertTrue(controller.state.value.player!!.currentPosition >= 5000)
                controller.undoSkip(); controller.pause()
                assertTrue(controller.state.value.player!!.currentPosition < 5000)
                assertNull(controller.state.value.undoSkipPosition)
                controller.close(); controller.open(item, label = "本地文件")
            }
            compose.waitUntil(20000) { controller.state.value.ready }
            compose.runOnIdle { assertTrue(controller.state.value.skipsEnabled); assertEquals(1, controller.state.value.skipRanges.size) }
        } finally {
            compose.runOnIdle {
                if (created) { controller.state.value.skipRanges.toList().forEach(controller::removeSkip); controller.close() }
                scope.cancel()
            }
            file.delete()
        }
    }

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
                val view = VodPlayerView(compose.activity) { controller.seekBy(it) }
                view.player = controller.state.value.player
                view.hideController()
                val before = controller.state.value.player!!.currentPosition
                assertTrue(view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)))
                assertTrue(view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT)))
                assertTrue(controller.state.value.player!!.currentPosition <= before - 9000)
                view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                assertTrue(controller.state.value.player!!.currentPosition >= before - 1000)
                view.player = null
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
