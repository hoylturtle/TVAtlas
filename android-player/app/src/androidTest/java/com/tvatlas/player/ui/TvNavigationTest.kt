@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.tvatlas.core.model.Channel
import com.tvatlas.core.model.Stream
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.storage.Library
import com.tvatlas.player.playback.PlaybackStatus
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TvNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun liveNavigationAnchorsLeftHidesChannelOverlayAndClosesOnSelection() {
        var selected = -1
        compose.setContent { AtlasTheme {
            TvBrowseLayout(true, "fixture", channels = { Text("频道列表测试") }, picture = { Text("画面测试") },
                navigation = { dismiss -> TvNavigation(0, { selected = it; dismiss() }, Modifier.fillMaxSize()) })
        } }
        compose.onNodeWithContentDescription("导航").performClick()
        compose.onNodeWithText("频道列表测试").assertDoesNotExist()
        val window = compose.onNodeWithTag("live-navigation-window").fetchSemanticsNode().boundsInRoot
        val drawer = compose.onNodeWithTag("live-navigation-drawer").fetchSemanticsNode().boundsInRoot
        assertEquals(window.left, drawer.left, 1f)
        compose.onNodeWithTag("main-nav:1").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithTag("live-navigation-drawer").assertDoesNotExist()
        compose.onNodeWithText("频道列表测试").assertIsDisplayed()
    }
    @Test fun browsingKeepsPictureSeparateAndRemoteCanSelectChannel() {
        val channel = Channel("fixture", "翡翠台", "香港 · 综合", streams = listOf(Stream("s", "https://example.com/live.m3u8", "p")))
        val library = Library(emptyList(), listOf(channel), emptyList(), RuleConfig())
        var played = false
        compose.setContent {
            MaterialTheme(colorScheme = colors) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, LocalTvMode provides true) {
                Row(Modifier.requiredSize(960.dp, 540.dp).background(MaterialTheme.colorScheme.background).testTag("tv-layout")) {
                    TvNavigation(0, {}, Modifier.fillMaxHeight())
                    Box(Modifier.weight(1f)) { TvBrowseLayout(true, "翡翠台",
                        { ChannelList(library, PlaybackStatus(), true, Modifier.fillMaxSize(), { played = true }, {}, {}) },
                        { PlayerPaneLayout(true, Modifier.fillMaxSize(), { Text("播放预览") },
                            { VideoControls("正在播放", "官方线路", "香港节点", true, true, {}, {}, {}) }) }) }
                }
            } }
        }
        compose.onNodeWithText("香港", substring = false).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("channel:fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("channel:fixture").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(true, played) }
        val bitmap = compose.onNodeWithTag("tv-layout").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "tv-layout.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
            }
            val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            File(context.cacheDir, "tv-layout.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun selectedPageAndRemoteFocusHaveDifferentFillColors() {
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            val input = androidx.compose.ui.platform.LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            TvNavigation(3, {})
        } } }
        compose.onNodeWithText("直播").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("直播").assertIsFocused().assertIsNotSelected()
        compose.onNodeWithText("设置").assertIsSelected().assertIsNotFocused()
        fun background(label: String): Int {
            val pixels = compose.onNodeWithText(label).captureToImage().toPixelMap()
            return pixels[pixels.width - 12, pixels.height / 2].toArgb()
        }
        assertEquals(colors.primary.toArgb(), background("设置"))
        org.junit.Assert.assertNotEquals(colors.primary.toArgb(), background("直播"))
    }
    @Test fun remoteCanMoveAndConfirmWithoutTouch() {
        var selected = 0
        compose.setContent { MaterialTheme(colorScheme = colors) { TvNavigation(selected, { selected = it }) } }
        compose.onNodeWithText("直播").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("直播").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("影视").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(4, selected) }
        compose.onNodeWithText("影视").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("频道源").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("频道源").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        compose.onNodeWithText("设置").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(3, selected) }
    }
}
