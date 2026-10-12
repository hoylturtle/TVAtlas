package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.SubscriptionFeedback
import com.tvatlas.player.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RoutingPageTest {
    @get:Rule val compose = createComposeRule()
    private val hk = ProxyProfile("hk", "香港节点", ProxyType.MIHOMO, "private.example", 443, subscriptionId = "sub")
    private val us = hk.copy(id = "us", name = "美国节点")
    private val library = Library(emptyList(), emptyList(), listOf(hk, us), RuleConfig())
    private val subscriptions = listOf(SubscriptionRow("sub", "艾可云", 1791763200000, 2))
    @Test fun phoneStartsWithSubscriptionManagementAndNodesCanFilterHongKong() {
        compose.setContent { MaterialTheme(colorScheme = colors) {
            Surface(Modifier.width(360.dp).height(650.dp).testTag("phone-proxy-layout"), color = MaterialTheme.colorScheme.background) {
                RoutingPage(library, {}, {}, {}, {}, subscriptions, false, {}, {}, {}, {})
            }
        } }
        val bitmap = compose.onNodeWithTag("phone-proxy-layout").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "phone-proxy-layout.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("导入规则").assertDoesNotExist()
        compose.onNodeWithText("香港节点").assertDoesNotExist()
        compose.onNodeWithText("艾可云").assertExists()
        compose.onNodeWithText("查看节点").performScrollTo().performClick()
        compose.onNodeWithText("只看香港节点").performScrollTo().performClick()
        compose.onNodeWithText("香港节点").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("美国节点").assertDoesNotExist()
        compose.onNodeWithText("private.example", substring = true).assertDoesNotExist()
    }
    @Test fun updateErrorRemainsOnSubscriptionCardAndRetryIsAvailable() {
        var refreshed: String? = null
        compose.setContent { MaterialTheme { RoutingPage(library, {}, {}, {}, {}, subscriptions, false, {},
            { refreshed = it }, {}, {}, feedback = SubscriptionFeedback("sub", "连接订阅服务超时；已保留旧节点", true)) } }
        compose.onNodeWithText("连接订阅服务超时；已保留旧节点").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("更新订阅").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("sub", refreshed) }
    }
}
