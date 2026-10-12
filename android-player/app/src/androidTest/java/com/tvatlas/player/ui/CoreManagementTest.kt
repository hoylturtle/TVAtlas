package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tvatlas.player.proxy.CoreInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CoreManagementTest {
    @get:Rule val compose = createComposeRule()
    @Test fun coreInfoOffersAppUpdateRatherThanSeparateCoreDownload() {
        var checked = false
        compose.setContent { MaterialTheme { CoreManagement(CoreInfo("v1.19.32", "arm64-v8a", true, false), { checked = true }) } }
        compose.onNodeWithText("当前版本：v1.19.32").assertIsDisplayed()
        compose.onNodeWithText("架构：arm64-v8a").assertIsDisplayed()
        compose.onNodeWithText("检查 APP 更新").performClick()
        compose.onNodeWithText("下载内核更新").assertDoesNotExist()
        compose.runOnIdle { assertTrue(checked) }
    }
}
