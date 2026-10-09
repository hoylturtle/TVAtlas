package com.tvatlas.player.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubscriptionPasteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pasteSubscriptionAddressPreservesCompleteUrlAndEnablesImport() {
        val url = "https://example.com/clash.yaml?token=fixture%2Btoken&format=clash"
        var saved: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme { SubscriptionDialog(false, {}) { name, address -> saved = name to address } }
        }
        compose.onNodeWithText("订阅名称").performTextInput("我的订阅")
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("subscription", "  $url\n"))
        }
        // Android can deny clipboard reads transiently while window focus changes.
        compose.waitUntil(timeoutMillis = 10000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ready = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() == url
            }
            ready
        }
        compose.onNodeWithText("粘贴订阅地址").performClick()
        compose.onNode(hasSetTextAction() and hasText(url)).assertExists()
        compose.onNodeWithText("下载并导入").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("我的订阅" to url, saved) }
    }
}
