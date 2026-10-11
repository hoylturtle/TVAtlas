package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DebugLogPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun showsFailuresExportsAndConfirmsClear() {
        var exported = false
        var cleared = false
        compose.setContent {
            var lines by remember { mutableStateOf(listOf("HTTP 403 访客会话失败")) }
            MaterialTheme { DebugLogDialog(lines, { "safe snapshot" }, {}, { lines = emptyList(); cleared = true }, { exported = true }) }
        }
        compose.onNodeWithText("HTTP 403 访客会话失败").assertExists()
        compose.onNodeWithText("导出 TXT").performClick()
        compose.runOnIdle { assertTrue(exported) }
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("确认清空").performClick()
        compose.onNodeWithText("暂无日志，请重试播放后查看").assertExists()
        compose.runOnIdle { assertTrue(cleared) }
    }
}
