package com.tvatlas.player.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ImmersivePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("immersive-player").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pictureUsesWholeViewportAndTouchTogglesOverlay() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme(colorScheme = colors) {
            ImmersivePlaybackLayout(true, false, Modifier.size(640.dp, 360.dp),
                picture = { Box(Modifier.fillMaxSize().testTag("picture")) },
                controls = { VideoControls("正在播放", "线路", "香港", true, true, {}, {}, {}, fullscreenLabel = "退出全屏") })
        } }
        compose.onNodeWithTag("picture", useUnmergedTree = true).assertWidthIsEqualTo(640.dp).assertHeightIsEqualTo(360.dp)
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(5100)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
        capture("fullscreen-layout.png")
        compose.onNodeWithTag("picture", useUnmergedTree = true).performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        compose.onNodeWithTag("picture", useUnmergedTree = true).performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
    }

    @Test fun stoppingShowsReplayAndNeverHidesIt() {
        var playing by mutableStateOf(true)
        var replays = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme(colorScheme = colors) {
            ImmersivePlaybackLayout(playing, false, Modifier.size(640.dp, 360.dp),
                picture = { Text(if (playing) "画面" else "已停止播放") },
                controls = { VideoControls(if (playing) "正在播放" else "已停止播放", "线路", "香港", true, playing,
                    {}, {}, { playing = false }, canReplay = !playing, onReplay = { replays++; playing = true }, fullscreenLabel = "退出全屏") })
        } }
        compose.onNodeWithText("停止").performClick()
        compose.mainClock.advanceTimeBy(10000)
        compose.onNodeWithText("重新播放").assertIsDisplayed().assertIsEnabled()
        capture("fullscreen-stopped-layout.png")
        compose.onNodeWithText("重新播放").performClick()
        compose.runOnIdle { assertEquals(1, replays); assertTrue(playing) }
        compose.onNodeWithText("停止").assertIsDisplayed()
    }

    @Test fun remoteCanRevealControlsAndOperateReplay() {
        var playing by mutableStateOf(true)
        var replays = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalTvMode provides true) {
            MaterialTheme(colorScheme = colors) {
                ImmersivePlaybackLayout(playing, true, Modifier.size(640.dp, 360.dp),
                    picture = { Box(Modifier.fillMaxSize().testTag("picture")) },
                    controls = { VideoControls("翡翠台", "线路", "香港", true, playing, {}, {}, { playing = false },
                        canReplay = !playing, onReplay = { replays++; playing = true }, fullscreenLabel = "退出全屏") })
            }
        } }
        compose.mainClock.advanceTimeBy(5100)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("线路").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("线路").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("重新播放").assertIsDisplayed()
        compose.onNodeWithText("重新播放").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("重新播放").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, replays) }
    }

    @Test fun systemBarsHideInFullscreenAndRestoreOnBack() {
        var immersive by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent {
            PlaybackSystemBars(immersive)
            BackHandler(immersive) { immersive = false }
            Box(Modifier.fillMaxSize()) { Text("画面") }
        }
        compose.waitUntil(5000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.systemBars())
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            !immersive && insets != null && insets.isVisible(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Test fun phoneStoppedControlsAlsoOfferReplayOutsideFullscreen() {
        var replays = 0
        compose.setContent { MaterialTheme(colorScheme = colors) {
            PlayerPaneLayout(false, Modifier.width(320.dp), picture = { Text("已停止播放") },
                controls = { VideoControls("已停止播放", "线路", "", true, false, {}, {}, {}, canReplay = true, onReplay = { replays++ }) })
        } }
        compose.onNodeWithText("重新播放").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, replays) }
        compose.onNodeWithText("停止").assertDoesNotExist()
    }
}
