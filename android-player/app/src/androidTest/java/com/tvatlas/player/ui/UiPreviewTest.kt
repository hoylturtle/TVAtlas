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
import com.tvatlas.core.vod.CatalogMovie
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
        var short by mutableStateOf(false)
        val channel = Channel("jade", "翡翠台", "香港 · 综合", streams = listOf(Stream("s", "https://www.mytvsuper.com/tc/live/81/", DefaultPlaylists.entries.first().id)))
        val profile = ProxyProfile("hk", "香港 02", ProxyType.MIHOMO, "fixture.invalid", 443, subscriptionId = "sub")
        val channels = listOf(channel,
            channel.copy(id = "pearl", name = "明珠台"), channel.copy(id = "plus", name = "TVB Plus"),
            channel.copy(id = "viu", name = "ViuTV"),
            channel.copy(id = "cctv", name = "CCTV1", group = "中国大陆 · 央视"),
            channel.copy(id = "gd", name = "广东卫视", group = "中国大陆 · 卫视"),
            channel.copy(id = "tw", name = "TVBS新闻", group = "台湾 · 新闻"),
            channel.copy(id = "mo", name = "澳视澳门", group = "澳门 · 综合"),
            channel.copy(id = "jp", name = "NHK総合", group = "日本 · 综合"),
            channel.copy(id = "kr", name = "KBS1", group = "韩国 · 综合"))
        val library = Library(DefaultPlaylists.entries, channels, listOf(profile,
            profile.copy(id = "hk2", name = "香港备用"), profile.copy(id = "tw1", name = "台湾 01")), RuleConfig())
        compose.setContent {
            AtlasTheme { CompositionLocalProvider(LocalTvMode provides tv) {
                key(tv, short, screen) {
                    Box(Modifier.requiredSize(if (short) 840.dp else if (tv) 960.dp else 360.dp, if (short) 320.dp else if (tv) 540.dp else 780.dp).testTag("preview")) {
                        Column(Modifier.atlasBackdrop()) {
                        if (!tv) AtlasAppBar({}, { screen = 3 })
                        Row(Modifier.weight(1f)) {
                            if (tv && screen != 0) TvNavigation(screen, {})
                            Box(Modifier.weight(1f).background(if (screen == 0) MaterialTheme.colorScheme.background else AtlasPageStyle.canvas)) {
                                val channels: @Composable (Modifier) -> Unit = { modifier -> ChannelList(library, PlaybackStatus(channelId = channel.id), tv, modifier, {}, {}, {}, autoExpand = true) }
                                val controls: @Composable () -> Unit = { VideoControls("准备就绪", "TVAtlas 默认频道", "艾可云 · 香港 02", true, false, {}, {}, {}, immersive = tv,
                                    channel = channel, tail = { Text("延迟 — ms · 网速 0 B/s", style = MaterialTheme.typography.labelSmall) }) }
                                val picture: @Composable (Modifier) -> Unit = { modifier ->
                                    if (tv) ImmersivePlaybackLayout(false, true, modifier, picture = { AtlasIdlePicture("选择频道开始观看") }, controls = controls)
                                    else PlayerPaneLayout(true, modifier, { AtlasIdlePicture("选择频道开始观看") }, controls)
                                }
                                when (screen) {
                                    0 -> if (tv) TvBrowseLayout(true, "翡翠台", { channels(Modifier.fillMaxSize()) }, { picture(Modifier.fillMaxSize()) }, { TvNavigation(0, {}) })
                                        else MobileLiveLayout(true, picture = picture, channels = channels)
                                    1 -> PlaylistPage(library, false, {}, {}, {})
                                    2 -> RoutingPage(library, {}, {}, {}, {}, listOf(SubscriptionRow("sub", "艾可云", 1791946800000, 3, 1024, 38L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024, quotaUpdatedAt = 1791946800000)), false, {}, {}, {}, {})
                                    4 -> Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        VodLibraryToolbar("影视片库", "年份优先 · 分类浏览 · 高清优先", short, onSearch = {}, onFilter = {}, onFiles = {})
                                        AtlasTabs(listOf("电影", "剧集", "综艺", "动漫", "短剧"), 0, {})
                                        VodMovieGrid((1..12).map { CatalogMovie("fixture$it", "示例影片 $it · 完整片名", "动作片", emptyList()) }, null, false,
                                            Modifier.weight(1f), restoreFocusId = if (tv) "fixture1" else null, onSelect = {})
                                        VodLibraryFooter(12, false, false, true, {}, {})
                                    }
                                    3 -> SettingsPage(false, {}, com.tvatlas.player.update.UpdateState(), {}, {})
                                }
                            }
                        }
                        if (!tv) AtlasBottomNavigation(screen) { screen = it }
                        }
                    }
                }
            } }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (mode in 0..2) for (page in (if (mode == 2) 1..4 else 0..4)) {
            val television = mode != 0
            compose.runOnIdle { tv = television; short = mode == 2; screen = page }
            compose.waitForIdle()
            if (television && page == 3) {
                compose.onNodeWithText("界面与遥控器").performClick()
                compose.waitForIdle()
            }
            if (page == 0) {
                compose.onNodeWithText("翡翠台", useUnmergedTree = true).performScrollTo()
                compose.waitForIdle()
            }
            val bitmap = compose.onNodeWithTag("preview").captureToImage().asAndroidBitmap()
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "ui-${if (mode == 2) "short" else if (television) "tv" else "phone"}-$page.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
            }
            val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
