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
    @Test fun choosingCategoryFiltersDisplayedEventsAndExportSelection() {
        var exported: com.tvatlas.player.debug.LogCategory? = null
        val rows = listOf("2026-10-18 12:00:00.000 +0800 INFO STARTUP database-ready",
            "2026-10-18 12:00:01.000 +0800 INFO MANIFEST manifest-refresh")
        compose.setContent { MaterialTheme {
            DebugLogDialog(rows, { "full" }, {}, {}, {}, categoryExport = { exported = it })
        } }
        compose.onNodeWithText("启动日志", substring = false).performClick()
        compose.onNodeWithText(rows[0]).assertExists()
        compose.onNodeWithText(rows[1]).assertDoesNotExist()
        compose.onNodeWithText("导出 TXT").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(com.tvatlas.player.debug.LogCategory.STARTUP, exported) }
    }
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
        compose.onNodeWithText("暂无此类日志").assertExists()
        compose.runOnIdle { assertTrue(cleared) }
    }
}
