package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
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
        val bitmap = compose.onNodeWithTag("phone-recovery-layout").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "phone-recovery-layout.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("重试播放").assertIsDisplayed().performClick()
        compose.onNodeWithText("切换线路").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, choices) }
    }
}
