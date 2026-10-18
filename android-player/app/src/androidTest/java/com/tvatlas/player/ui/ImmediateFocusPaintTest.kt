@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImmediateFocusPaintTest {
    @get:Rule val compose = createComposeRule()
    @Test fun channelLoadingKeepsProgressThinInsideFullSizePanel() {
        compose.setContent { AtlasTheme {
            Box(Modifier.size(360.dp, 480.dp).testTag("loading-panel")) {
                ChannelBrowseLoading(Modifier.fillMaxSize())
            }
        } }
        compose.onNodeWithText("正在准备频道列表…").assertIsDisplayed()
        compose.onNodeWithTag("channel-loading-progress").assertHeightIsEqualTo(4.dp)
        val panel = compose.onNodeWithTag("loading-panel").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithTag("channel-loading-progress").fetchSemanticsNode().boundsInRoot
        assertTrue(progress.height < panel.height / 20)
        assertTrue(progress.width < panel.width)
    }
    @Test fun remoteFocusRepaintsBothItemsWithoutRecomposingTheirContent() {
        val first = FocusRequester()
        var compositions = 0
        compose.setContent { AtlasTheme {
            SideEffect { compositions++ }
            Row {
                Box(Modifier.size(80.dp).background(Color.Black).focusRequester(first)
                    .tvFocusOutline().focusable().testTag("first"))
                Box(Modifier.size(80.dp).background(Color.Black).tvFocusOutline().focusable().testTag("second"))
            }
        } }
        compose.runOnIdle { first.requestFocus() }
        fun blue(tag: String): Float {
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2].blue
        }
        val before = compositions
        assertTrue(blue("first") > .1f)
        compose.onNodeWithTag("first").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("second").assertIsFocused()
        assertEquals(0f, blue("first"), .001f)
        assertTrue(blue("second") > .1f)
        compose.runOnIdle { assertEquals(before, compositions) }
    }
}
