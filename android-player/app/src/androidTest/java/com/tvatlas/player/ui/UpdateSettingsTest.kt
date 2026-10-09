package com.tvatlas.player.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.player.BuildConfig
import com.tvatlas.player.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UpdateSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun newerVersionOffersDownloadAndCopiesLinkForTv() {
        val link = "https://github.com/hoylturtle/TVAtlas/actions/runs/123/artifacts/456"
        var download = false
        val update = UpdateState(checked = true, release = PublishedUpdate(BuildConfig.VERSION_CODE + 1, "0.1.4", 23, "修复播放", link))
        compose.setContent { MaterialTheme { SettingsPage(false, {}, update, {}, { download = true }) } }
        compose.onNodeWithText("发现新版本：0.1.4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("打开下载页面").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(download) }
        compose.onNodeWithText("复制下载链接").performScrollTo().performClick()
        compose.onNodeWithText("下载链接已复制").assertExists()
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
        compose.onNodeWithText("打开下载页面").assertDoesNotExist()
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(checked) }
    }
}
