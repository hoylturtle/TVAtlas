package com.tvatlas.player.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.player.BuildConfig
import com.tvatlas.core.model.*
import com.tvatlas.player.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UpdateSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun repositoryAndCopyRemainAvailableAfterUpdateNetworkFailure() {
        var opened = false
        val update = UpdateState(error = "连接 GitHub 失败")
        compose.setContent { MaterialTheme { SettingsPage(false, {}, update, {}, {}, onRepository = { opened = true }) } }
        compose.onNodeWithText("连接 GitHub 失败").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("打开 GitHub 仓库").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithText("复制仓库链接").performScrollTo().performClick()
        compose.onNodeWithText("仓库链接已复制").assertExists()
    }
    @Test fun updateNetworkCanSelectEnabledImportedProxyByName() {
        val profile = ProxyProfile("private-id", "香港更新节点", ProxyType.MIHOMO, "private.example", 443)
        var saved: RouteTarget? = null
        compose.setContent {
            var selected by remember { mutableStateOf(RouteTarget.DIRECT) }
            MaterialTheme { SettingsPage(false, {}, UpdateState(), {}, {}, profiles = listOf(profile),
                updateRoute = selected, onUpdateRoute = { selected = it; saved = it }) }
        }
        compose.onNodeWithText("直连").performScrollTo().performClick()
        compose.onNodeWithText("香港更新节点").performClick()
        compose.onNodeWithText("香港更新节点").assertExists()
        compose.onNodeWithText("private-id", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(RouteTarget.proxy(profile.id), saved) }
    }
    @Test fun newerVersionOffersDownloadAndCopiesLinkForTv() {
        val link = "https://github.com/hoylturtle/TVAtlas/actions/runs/123/artifacts/456"
        var download = false
        val update = UpdateState(checked = true, release = PublishedUpdate(BuildConfig.VERSION_CODE + 1, "0.1.5", 23, "修复播放", link))
        compose.setContent { MaterialTheme { SettingsPage(false, {}, update, {}, { download = true }) } }
        compose.onNodeWithText("发现新版本：0.1.5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下载新版 APK").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(download) }
        compose.onNodeWithText("复制下载链接").performScrollTo().performClick()
        compose.onNodeWithText("下载链接已复制").assertExists()
        // Android can deny clipboard reads transiently while window focus changes.
        compose.waitUntil(timeoutMillis = 10000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ready = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() == link
            }
            ready
        }
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(link, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun sameVersionShowsUpToDateAndManualCheckWorks() {
        var checked = false
        val update = UpdateState(checked = true, release = PublishedUpdate(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, 23, "", ""))
        compose.setContent { MaterialTheme { SettingsPage(false, {}, update, { checked = true }, {}) } }
        compose.onNodeWithText("已是最新版本").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下载新版 APK").assertDoesNotExist()
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(checked) }
    }
}
