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
    @Test fun openChannelPanelKeepsItsControlStripVisible() {
        var pinned by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AtlasTheme {
            ImmersivePlaybackLayout(true, false, Modifier.requiredSize(960.dp, 540.dp), picture = {}, controls = { Text("示例直播控制栏") }, keepControlsVisible = pinned)
        } }
        compose.mainClock.advanceTimeBy(7000)
        compose.onNodeWithText("示例直播控制栏").assertIsDisplayed()
        compose.runOnIdle { pinned = false }
        compose.mainClock.advanceTimeBy(6000)
        compose.onNodeWithText("示例直播控制栏").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
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
            Channel("hk$index", name, "香港 · TVB", streams = listOf(Stream("sample$index", "https://fixture.invalid/live/$index", DefaultPlaylists.entries.first().id)))
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
            if (index == 3) {
                compose.onNodeWithText("更新与诊断").performClick()
                compose.onNodeWithText("诊断与日志").assertIsDisplayed()
            }
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
        val names = listOf("翡翠台", "明珠台", "TVB Plus", "TVB 新闻台", "ViuTV", "ViuTVsix", "港台电视31", "港台电视32")
        val channels = names.mapIndexed { index, name -> Channel("c${index + 1}", name, "香港 · TVB", streams = emptyList()) } +
            listOf("大陆", "台湾", "澳门", "日本", "韩国").mapIndexed { index, label -> Channel("other$index", "示例频道$index", "$label · 综合", streams = emptyList()) }
        val now = System.currentTimeMillis()
        val sampleGuide = listOf("新闻", "纪录片", "电影", "晚间新闻").mapIndexed { index, title ->
            com.tvatlas.core.epg.Programme("sample", now - 600000 + index * 3600000, now - 600000 + (index + 1) * 3600000, title)
        }
        val library = Library(emptyList(), channels, emptyList(), RuleConfig())
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            Box(Modifier.requiredSize(1280.dp, 720.dp).testTag("tv-split-preview")) {
                TvBrowseLayout(true, "翡翠台", channels = {
                    ChannelList(library, PlaybackStatus(channelId = "c1"), true, Modifier.fillMaxSize(), {}, {}, {}, autoExpand = true,
                        programme = { Text("示例节目", maxLines = 1) }, guide = {
                            ProgrammeSchedule(sampleGuide, now)
                        })
                }, picture = {
                    ImmersivePlaybackLayout(true, true, Modifier.fillMaxSize(), picture = { Box(Modifier.fillMaxSize().background(Color(0xFF244A50))) }, controls = {
                        VideoControls("正在播放", "官方线路", "香港代理 · 示例订阅 / 香港01", true, true, {}, {}, {}, immersive = true,
                            fullscreenLabel = "收起", channel = channels.first(), tail = { Text("节点延迟 46 ms · 1.8 MB/s", style = MaterialTheme.typography.labelMedium) })
                    }, keepControlsVisible = true)
                }, navigation = { TvNavigation(0, {}) })
            }
        } } }
        val categories = compose.onNodeWithTag("channel-categories").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("channel-list").fetchSemanticsNode().boundsInRoot
        assertTrue(categories.right < grid.left)
        val cards = (1..3).map { compose.onNodeWithTag("channel:c$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(1, cards.map { it.left }.distinct().size)
        assertEquals(3, cards.map { it.top }.distinct().size)
        assertTrue(cards[0].top < cards[1].top && cards[1].top < cards[2].top)
        compose.onNodeWithTag("channel:c1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("channel:c1").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("channel-guide").assertIsDisplayed()
        compose.onNodeWithText("收起节目单").assertIsFocused()
        compose.onNodeWithText("今日节目").assertIsDisplayed()
        compose.onNodeWithTag("channel:c8").assertIsDisplayed()
        val categoriesWithGuide = compose.onNodeWithTag("channel-categories").fetchSemanticsNode().boundsInRoot
        val channelWithGuide = compose.onNodeWithTag("channel-list").fetchSemanticsNode().boundsInRoot
        val guideBounds = compose.onNodeWithTag("channel-guide").fetchSemanticsNode().boundsInRoot
        assertTrue(categoriesWithGuide.right < channelWithGuide.left)
        assertTrue(channelWithGuide.right < guideBounds.left)
        compose.onNodeWithTag("channel:c2").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        capture("tv-channel-programme.png")
        compose.onNodeWithText("收起节目单").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("收起节目单").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("channel:c2").assertIsFocused()
        compose.onNodeWithTag("channel-guide").assertDoesNotExist()
        capture("tv-channel-list.png")
    }
}
