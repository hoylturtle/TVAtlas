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
