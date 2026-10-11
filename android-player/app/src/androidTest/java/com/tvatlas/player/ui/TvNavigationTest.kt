@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
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
    @Test fun browsingKeepsPictureSeparateAndRemoteCanSelectChannel() {
        val channel = Channel("fixture", "翡翠台", "香港 · 综合", streams = listOf(Stream("s", "https://example.com/live.m3u8", "p")))
        val library = Library(emptyList(), listOf(channel), emptyList(), RuleConfig())
        var played = false
        compose.setContent {
            MaterialTheme(colorScheme = colors) { CompositionLocalProvider(LocalTvMode provides true, LocalDensity provides Density(1f)) {
                Row(Modifier.requiredSize(960.dp, 540.dp).testTag("tv-layout")) {
                    TvNavigation(0, {}, Modifier.fillMaxHeight())
                    Box(Modifier.weight(1f)) { TvBrowseLayout(true, "翡翠台",
                        { ChannelList(library, PlaybackStatus(), true, Modifier.fillMaxSize(), { played = true }, {}, {}) },
                        { PlayerPaneLayout(true, Modifier.fillMaxSize(), { Text("播放预览") },
                            { VideoControls("正在播放", "官方线路", "香港节点", true, true, {}, {}, {}) }) }) }
                }
            } }
        }
        compose.onNodeWithText("›  香港 · 综合").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("⌄  香港 · 综合").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onAllNodesWithText("翡翠台").filterToOne(isFocusable()).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(true, played) }
        val bitmap = compose.onNodeWithTag("tv-layout").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "tv-layout.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun remoteCanMoveAndConfirmWithoutTouch() {
        var selected = 0
        compose.setContent { MaterialTheme(colorScheme = colors) { TvNavigation(selected, { selected = it }) } }
        compose.onNodeWithText("直播").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("直播").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("播放列表").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("播放列表").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        compose.onNodeWithText("设置").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(3, selected) }
    }
}
