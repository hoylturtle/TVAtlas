package com.tvatlas.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tvatlas.player.traffic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TrafficStatisticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun totalsNodeDetailsAndResetConfirmation() {
        var resets = 0
        compose.setContent { MaterialTheme(colorScheme = colors) {
            TrafficStatisticsCard(TrafficSnapshot(entries = listOf(
                TrafficUsage("DIRECT", "直连", false, 1024, 1024),
                TrafficUsage("PROXY:hk", "香港一", true, 2048, 1024))), { resets++ })
        } }
        compose.onNodeWithText("2.0 KiB").assertIsDisplayed()
        compose.onNodeWithText("3.0 KiB").assertIsDisplayed()
        compose.onNodeWithText("节点明细").performClick()
        compose.onNodeWithText("香港一").assertIsDisplayed()
        compose.onNodeWithText("下载 2.0 KiB · 上传 1.0 KiB").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("重置统计").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("重置统计").performClick()
        compose.onNodeWithText("重置", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }
    @Test fun committedSelectionUsesLeadingMarkerAndClearsImmediately() {
        var selected by mutableStateOf(true)
        compose.setContent { MaterialTheme(colorScheme = colors) {
            Box(Modifier.size(100.dp).background(Color.Black).selectionOutline(selected).testTag("selection"))
        } }
        val bitmap = compose.onNodeWithTag("selection").captureToImage().toPixelMap()
        assertEquals(Color.Black, bitmap[bitmap.width / 2, bitmap.height / 2])
        assertEquals(colors.primary, bitmap[1, bitmap.height / 2])
        compose.runOnIdle { selected = false }
        val changed = compose.onNodeWithTag("selection").captureToImage().toPixelMap()
        assertEquals(Color.Black, changed[changed.width / 2, changed.height / 2])
        assertEquals(Color.Black, changed[1, changed.height / 2])
    }
}
