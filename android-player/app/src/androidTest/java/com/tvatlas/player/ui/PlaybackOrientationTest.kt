package com.tvatlas.player.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class PlaybackOrientationTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()
    @Before fun portrait() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitDirection(Configuration.ORIENTATION_PORTRAIT)
    }
    @After fun restoreEmulatorDisplay() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitDirection(Configuration.ORIENTATION_LANDSCAPE)
    }
    private fun awaitDirection(expected: Int) {
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == expected }
    }

    @Test fun mobileFullscreenOverridesPortraitLockAndBackRestoresItWithoutLosingReplay() {
        var fullscreen by mutableStateOf(false)
        var playing by mutableStateOf(true)
        val originalActivity = compose.activity
        compose.setContent { MaterialTheme(colorScheme = colors) {
            PlaybackOrientation(fullscreen, tv = false)
            BackHandler(fullscreen) { fullscreen = false }
            Column(Modifier.fillMaxSize()) {
                VideoControls("翡翠台", "官方线路", "香港", true, playing, {}, { fullscreen = !fullscreen },
                    { playing = false }, canReplay = !playing, onReplay = { playing = true },
                    fullscreenLabel = if (fullscreen) "退出全屏" else "全屏 / 频道")
            }
        } }
        repeat(2) {
            compose.onNodeWithText("全屏 / 频道").performClick()
            awaitDirection(Configuration.ORIENTATION_LANDSCAPE)
            compose.runOnIdle {
                assertSame(originalActivity, compose.activity)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)
            }
            compose.onNodeWithText("停止").performClick()
            compose.onNodeWithText("重新播放").assertIsDisplayed().performClick()
            compose.runOnIdle { assertTrue(fullscreen); assertTrue(playing) }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            awaitDirection(Configuration.ORIENTATION_PORTRAIT)
            compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation) }
        }
    }

    @Test fun exitButtonRestoresAnExistingReverseLandscapePolicy() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE }
        awaitDirection(Configuration.ORIENTATION_LANDSCAPE)
        var fullscreen by mutableStateOf(true)
        compose.setContent { MaterialTheme(colorScheme = colors) {
            PlaybackOrientation(fullscreen, tv = false)
            VideoControls("正在播放", "线路", "", true, true, {}, { fullscreen = false }, {}, fullscreenLabel = "退出全屏")
        } }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation) }
        compose.onNodeWithText("退出全屏").performClick()
        compose.runOnIdle {
            assertFalse(fullscreen)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, compose.activity.requestedOrientation)
        }
    }

    @Test fun televisionKeepsItsPolicyAndRemovingMobilePlaybackRestoresOrientation() {
        var tv by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        compose.setContent {
            if (mounted) PlaybackOrientation(immersive = true, tv = tv)
            Box(Modifier.fillMaxSize())
        }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation); tv = false }
        awaitDirection(Configuration.ORIENTATION_LANDSCAPE)
        compose.runOnIdle { mounted = false }
        awaitDirection(Configuration.ORIENTATION_PORTRAIT)
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation) }
    }
}
