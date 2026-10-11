@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TvNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun remoteCanMoveAndConfirmWithoutTouch() {
        var selected = 0
        compose.setContent { MaterialTheme { TvNavigation(selected, { selected = it }) } }
        compose.onNodeWithText("直播").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("直播").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("播放列表").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("播放列表").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        compose.onNodeWithText("设置").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(3, selected) }
    }
}
