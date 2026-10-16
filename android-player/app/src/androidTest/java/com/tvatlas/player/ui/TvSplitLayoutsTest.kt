@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvSplitLayoutsTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("tv-split-preview").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas-next")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun shortLandscapeNavigationCanScrollToSettings() {
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            Box(Modifier.requiredSize(132.dp, 280.dp)) { MainNavigationRail(true, false, false, 0, {}) }
        } } }
        compose.onNodeWithTag("main-nav:3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("main-nav:0").performScrollTo().assertIsDisplayed()
    }
    @Test fun stoppedHomeExposesAllPagesAndPlaybackHidesNavigation() {
        val channels = listOf("翡翠台", "明珠台", "TVB Plus", "ViuTV", "ViuTVsix", "港台电视31").mapIndexed { index, name ->
            Channel("hk$index", name, "香港 · TVB", streams = emptyList())
        }
        val library = Library(DefaultPlaylists.entries, channels, emptyList(), RuleConfig())
        var page by mutableIntStateOf(0)
        var active by mutableStateOf(false)
        var replayed = false
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            Row(Modifier.requiredSize(1280.dp, 720.dp).atlasBackdrop().testTag("tv-split-preview")) {
                MainNavigationRail(true, false, active, page) { page = it }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (page) {
                        0 -> LiveHomeLayout("翡翠台", "已停止播放", { replayed = true; active = true }) {
                            ChannelList(library, PlaybackStatus(), true, Modifier.fillMaxSize(), {}, {}, {},
                                programme = { Text("正在播：示例节目", maxLines = 1) })
                        }
                        1 -> PlaylistPage(library, false, {}, {}, {})
                        2 -> RoutingPage(library, {}, {}, {}, {}, emptyList(), false, {}, {}, {}, {})
                        3 -> SettingsPage(false, {}, com.tvatlas.player.update.UpdateState(), {}, {})
                        4 -> CloudLibraryPage(com.tvatlas.player.cloud.CloudLibraryState(), { _, _, _, _ -> }, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        } } }
        compose.onNodeWithTag("main-navigation").assertIsDisplayed()
        capture("tv-home-stopped.png")
        for (index in listOf(4, 1, 2, 3, 0)) {
            compose.onNodeWithTag("main-nav:$index").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            compose.onNodeWithTag("main-nav:$index").performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertEquals(index, page) }
            compose.onNodeWithTag("main-navigation").assertIsDisplayed()
            capture("tv-page-$index.png")
        }
        compose.onNodeWithText("重新播放").performClick()
        compose.runOnIdle { assertTrue(replayed) }
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        compose.runOnIdle { active = false }
        compose.onNodeWithTag("main-navigation").assertIsDisplayed()
    }
    @Test fun proxyCardsHaveEqualSizesMultipleColumnsAndRemoteDetails() {
        val profiles = (0..15).map { ProxyProfile("p$it", "香港 ${it + 1} · 高速影音线路 BGP", ProxyType.HTTP, "127.0.0.1", 7890, subscriptionId = "sub") }
        val library = Library(emptyList(), emptyList(), profiles, RuleConfig())
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            Row(Modifier.requiredSize(1280.dp, 720.dp).atlasBackdrop().testTag("tv-split-preview")) {
                TvNavigation(2, {}, Modifier.fillMaxHeight())
                Box(Modifier.weight(1f)) { RoutingPage(library, {}, {}, {}, {}, listOf(SubscriptionRow("sub", "测试影音订阅", 0, profiles.size)), false, {}, {}, {}, {}) }
            }
        } } }
        val bounds = (0..5).map { compose.onNodeWithTag("proxy-node:p$it").fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.map { it.left }.distinct().size >= 3)
        assertTrue(bounds.map { it.top }.distinct().size >= 2)
        bounds.forEach { assertEquals(bounds.first().width, it.width, 1f); assertEquals(bounds.first().height, it.height, 1f) }
        capture("tv-proxy-grid.png")
        compose.onNodeWithTag("proxy-node:p0").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("proxy-node:p0").performKeyInput { pressKey(Key.Enter) }
        compose.onAllNodesWithText("节点详情").onFirst().assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        for (index in listOf(0, 2, 3, 4, 1)) {
            compose.onNodeWithTag("routing-section:$index").performClick()
            compose.onNodeWithTag("routing-content").assertExists()
        }
    }
    @Test fun channelCategoriesAndGridUseSeparateColumns() {
        val channels = (1..18).map { Channel("c$it", "CCTV-$it", "中国大陆 · CCTV", streams = emptyList()) } +
            Channel("jade", "翡翠台", "香港 · TVB", streams = emptyList())
        val library = Library(emptyList(), channels, emptyList(), RuleConfig())
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            Box(Modifier.requiredSize(1280.dp, 720.dp).testTag("tv-split-preview")) {
                TvBrowseLayout(true, "CCTV-1", channels = {
                    ChannelList(library, PlaybackStatus(channelId = "c1"), true, Modifier.fillMaxSize(), {}, {}, {}, autoExpand = true,
                        programme = { Text("正在播：示例节目", maxLines = 1) }, guide = {
                            Text("今日节目")
                            listOf("19:00 新闻", "19:30 纪录片", "20:30 电影", "22:30 晚间新闻").forEach { title ->
                                Text(title, Modifier.padding(vertical = 12.dp))
                            }
                        })
                }, picture = { Box(Modifier.fillMaxSize().background(Color(0xFF244A50))) }, navigation = { TvNavigation(0, {}) })
            }
        } } }
        val categories = compose.onNodeWithTag("channel-categories").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("channel-list").fetchSemanticsNode().boundsInRoot
        assertTrue(categories.right < grid.left)
        val cards = (1..3).map { compose.onNodeWithTag("channel:c$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(1, cards.map { it.left }.distinct().size)
        assertEquals(3, cards.map { it.top }.distinct().size)
        compose.onNodeWithTag("channel:c1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("channel:c1").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("channel-guide").assertIsDisplayed()
        compose.onNodeWithText("收起节目单").assertIsFocused()
        capture("tv-channel-programme.png")
        compose.onNodeWithText("收起节目单").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("channel:c1").assertIsFocused()
        compose.onNodeWithTag("channel-guide").assertDoesNotExist()
        capture("tv-channel-list.png")
    }
}
