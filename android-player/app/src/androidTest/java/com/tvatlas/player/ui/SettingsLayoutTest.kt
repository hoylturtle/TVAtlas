package com.tvatlas.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.player.BuildConfig
import com.tvatlas.player.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun phoneShowsUpdateFirstAndCollapsedHelpUsesTouchInstructions() {
        compose.setContent { CompositionLocalProvider(LocalTvMode provides false) {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.width(360.dp).height(650.dp).testTag("phone-settings-layout"), color = MaterialTheme.colorScheme.background) {
                    SettingsPage(false, {}, UpdateState(), {}, {})
                }
            }
        } }
        compose.onNodeWithText("尚未检查更新").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        compose.onNodeWithText("已是最新版本").assertDoesNotExist()
        val bitmap = compose.onNodeWithTag("phone-settings-layout").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "phone-settings-layout.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("操作帮助").performScrollTo().performClick()
        compose.onNodeWithText("直播：点频道播放", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("方向键选择", substring = true).assertDoesNotExist()
    }
    @Test fun verifiedApkOffersInstallWithoutRedownload() {
        var installed = false
        val release = PublishedUpdate(BuildConfig.VERSION_CODE + 1, "next", 23, "", "https://github.com/example/app.apk")
        compose.setContent { MaterialTheme { SettingsPage(false, {}, UpdateState(checked = true, release = release, apkReady = true), {}, {}, onInstall = { installed = true }) } }
        compose.onNodeWithText("安装包已校验，可以安装").assertIsDisplayed()
        compose.onNodeWithText("下载新版 APK").assertDoesNotExist()
        compose.onNodeWithText("安装更新").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(installed) }
    }
}
