package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackRecoveryTest {
    @get:Rule val compose = createComposeRule()
    @Test fun failureShowsActionableRetryAndRouteChoiceOnNarrowPhone() {
        var retries = 0
        var choices = 0
        compose.setContent { CompositionLocalProvider(LocalTvMode provides false) {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.width(320.dp).testTag("phone-recovery-layout")) {
                    PlaybackRecoveryCard("设备初始化网络连接失败（6002）", { retries++ }, { choices++ })
                }
            }
        } }
        compose.onNodeWithText("设备初始化网络连接失败（6002）").assertIsDisplayed()
        compose.onNodeWithText("重试播放").assertIsDisplayed().performClick()
        compose.onNodeWithText("切换线路").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, choices) }
    }
}
