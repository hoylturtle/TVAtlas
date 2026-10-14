package com.tvatlas.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.*
import org.junit.Rule
import org.junit.Test

class UiPreviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun capturePhoneAndTvScreens() {
        var screen by mutableIntStateOf(0)
        var tv by mutableStateOf(false)
        val channel = Channel("jade", "翡翠台", "香港 · 综合", streams = listOf(Stream("s", "https://www.mytvsuper.com/tc/live/81/", DefaultPlaylists.entries.first().id)))
        val profile = ProxyProfile("hk", "香港 02", ProxyType.MIHOMO, "fixture.invalid", 443, subscriptionId = "sub")
        val library = Library(DefaultPlaylists.entries, listOf(channel), listOf(profile), RuleConfig())
        compose.setContent {
            MaterialTheme(colorScheme = colors) { CompositionLocalProvider(LocalTvMode provides tv) {
                key(tv, screen) {
                    Surface(Modifier.requiredSize(if (tv) 960.dp else 360.dp, if (tv) 540.dp else 650.dp).testTag("preview")) {
                        Row {
                            if (tv && screen != 0) TvNavigation(screen, {})
                            Box(Modifier.weight(1f)) {
                                val channels: @Composable (Modifier) -> Unit = { modifier -> ChannelList(library, PlaybackStatus(), tv, modifier, {}, {}, {}) }
                                val picture: @Composable (Modifier) -> Unit = { modifier -> PlayerPaneLayout(true, modifier,
                                    { Text("选择频道开始观看") }, { VideoControls("准备就绪", "TVAtlas 默认频道", "艾可云 · 香港 02", true, false, {}, {}, {}, immersive = tv) }) }
                                when (screen) {
                                    0 -> if (tv) TvBrowseLayout(true, "翡翠台", { channels(Modifier.fillMaxSize()) }, { picture(Modifier.fillMaxSize()) }, { TvNavigation(0, {}) })
                                        else MobileLiveLayout(true, picture = picture, channels = channels)
                                    1 -> PlaylistPage(library, false, {}, {}, {})
                                    2 -> RoutingPage(library, {}, {}, {}, {}, listOf(SubscriptionRow("sub", "艾可云", 1791946800000, 19, 1024, 38L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024, quotaUpdatedAt = 1791946800000)), false, {}, {}, {}, {})
                                    3 -> SettingsPage(false, {}, com.tvatlas.player.update.UpdateState(), {}, {})
                                }
                            }
                        }
                    }
                }
            } }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (television in listOf(false, true)) for (page in 0..3) {
            compose.runOnIdle { tv = television; screen = page }
            compose.waitForIdle()
            if (television && page == 3) {
                compose.onNodeWithText("界面与遥控器").performClick()
                compose.waitForIdle()
            }
            val bitmap = compose.onNodeWithTag("preview").captureToImage().asAndroidBitmap()
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "ui-${if (television) "tv" else "phone"}-$page.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
            }
            val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
