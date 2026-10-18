@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.background

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Library
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChannelPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val first = Stream("s1", "https://private.example/stream?token=fixture", "p1")
    private val second = Stream("s2", "https://private.example/other?token=fixture", "p2")
    private val profile = ProxyProfile("sub_internal_identifier", "香港节点", ProxyType.MIHOMO, "private.example", 443)

    @Test fun everyChannelLogoUsesThemeSurfaceInsteadOfWhiteTiles() {
        val names = listOf("CCTV1", "湖南卫视", "翡翠台", "ViuTV")
        compose.setContent { AtlasTheme {
            Row(Modifier.background(colors.surface)) { names.forEachIndexed { index, name ->
                ChannelArtwork(Channel("logo$index", name, "综合", streams = listOf(first)))
            } }
        } }
        names.forEach { name ->
            val pixels = compose.onNodeWithContentDescription("${name}频道标识", useUnmergedTree = true)
                .captureToImage().toPixelMap()
            assertEquals("$name logo background", colors.surface.toArgb(), pixels[pixels.width / 2, 1].toArgb())
        }
    }

    @Test fun longChannelTitlesCannotExpandIntoOversizedCards() {
        val name = "频道名称很长".repeat(40)
        val channel = Channel("long", name, "中国大陆 · 卫视", streams = listOf(first))
        compose.setContent { AtlasTheme {
            ChannelList(Library(emptyList(), listOf(channel), emptyList(), RuleConfig()), PlaybackStatus(), false,
                Modifier, {}, {}, {}, autoExpand = true)
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height <= compose.density.run { 56.dp.toPx() })
    }
    @Test fun defaultBrowseShowsArtworkAndCanSwitchRegion() {
        val channel = Channel("jade", "翡翠台", "香港 · 综合", streams = listOf(first))
        compose.setContent { MaterialTheme { ChannelList(Library(emptyList(), listOf(channel, channel.copy(id = "cctv", name = "CCTV1", group = "中国大陆 · 央视")), emptyList(), RuleConfig()),
            PlaybackStatus(), false, Modifier, {}, {}, {}, autoExpand = true) } }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("翡翠台").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.onNodeWithText("翡翠台").assertIsDisplayed()
        compose.onNodeWithContentDescription("翡翠台频道标识", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("大陆", substring = false).performClick()
        compose.onNodeWithText("CCTV1").assertIsDisplayed()
        compose.onNodeWithText("翡翠台").assertDoesNotExist()
    }
    @Test fun tvCompactRegionNavigationCanPlayWithRemote() {
        val channel = Channel("jade", "翡翠台", "香港 · 综合", streams = listOf(first))
        var played = false
        compose.setContent { MaterialTheme { ChannelList(Library(emptyList(), listOf(channel), emptyList(), RuleConfig()),
            PlaybackStatus(), true, Modifier, { played = true }, {}, {}, autoExpand = true) } }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("全部", substring = false).assertIsFocused(); true }.getOrDefault(false) }
        compose.onNodeWithText("全部", substring = false).assertIsFocused()
        compose.onNodeWithText("香港", substring = false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("香港", substring = false).performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("channel:jade").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("channel:jade").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(played) }
    }
    @Test fun groupsStartCollapsedAndSearchShowsMatchesWithoutOpeningEveryGroup() {
        val channels = listOf(Channel("c1", "CCTV 新闻", "中国大陆 · 新闻", streams = listOf(first)), Channel("c2", "体育频道", "体育", streams = listOf(second)))
        val library = Library(emptyList(), channels, emptyList(), RuleConfig())
        compose.setContent { MaterialTheme { ChannelList(library, PlaybackStatus(), false, Modifier, {}, {}, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CCTV 新闻").assertDoesNotExist()
        compose.onNodeWithText("体育频道").assertDoesNotExist()
        compose.onNodeWithText("›  大陆").performClick()
        compose.onNodeWithText("CCTV 新闻").assertDoesNotExist()
        compose.onNodeWithText("›  CCTV").performClick()
        compose.onNodeWithText("CCTV 新闻").assertExists()
        compose.onNodeWithText("⌄  大陆").performClick()
        compose.onNodeWithText("CCTV 新闻").assertDoesNotExist()
        compose.onNodeWithText("搜索频道").performTextInput("体育")
        compose.onNodeWithText("体育频道").assertExists()
        compose.onNodeWithText("搜索频道").performTextClearance()
        compose.onNodeWithText("体育频道").assertDoesNotExist()
    }
    @Test fun tvInitiallyFocusesCollapsedGroupAndEnterExpandsIt() {
        val channel = Channel("c1", "新闻频道", "新闻", streams = listOf(first))
        val library = Library(emptyList(), listOf(channel), emptyList(), RuleConfig())
        compose.setContent { MaterialTheme { ChannelList(library, PlaybackStatus(), true, Modifier, {}, {}, {}) } }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("全部", substring = false).assertIsFocused(); true }.getOrDefault(false) }
        compose.onNodeWithText("全部", substring = false).assertIsFocused()
        compose.onNodeWithText("其他", substring = false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("其他", substring = false).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("新闻频道").assertExists()
    }
    @Test fun mainlandCctvAndSatelliteTypesExpandIndependently() {
        val channels = listOf(Channel("cctv", "CCTV1", "中国大陆 · 央视", streams = listOf(first)),
            Channel("gd", "广东卫视", "中国大陆 · 卫视", streams = listOf(second)))
        compose.setContent { MaterialTheme { ChannelList(Library(emptyList(), channels, emptyList(), RuleConfig()), PlaybackStatus(), false, Modifier, {}, {}, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("›  大陆").performClick()
        compose.onNodeWithText("›  CCTV").performClick()
        compose.onNodeWithText("CCTV1").assertIsDisplayed()
        compose.onNodeWithText("广东卫视").assertDoesNotExist()
        compose.onNodeWithText("›  卫视").performClick()
        compose.onNodeWithText("广东卫视").assertIsDisplayed()
        compose.onNodeWithText("⌄  CCTV").performClick()
        compose.onNodeWithText("CCTV1").assertDoesNotExist()
        compose.onNodeWithText("广东卫视").assertIsDisplayed()
    }
    @Test fun selectedProxyShowsItsNameAndNoInternalId() {
        compose.setContent {
            var selected by remember { mutableStateOf<RouteTarget?>(RouteTarget.DIRECT) }
            MaterialTheme { RoutePicker(listOf(profile), selected) { selected = it } }
        }
        compose.onNodeWithText("直连").performClick()
        compose.onNodeWithText("香港节点").performClick()
        compose.onNodeWithText("香港节点").assertExists()
        compose.onNodeWithText("sub_internal_identifier", substring = true).assertDoesNotExist()
        compose.onNodeWithText("private.example", substring = true).assertDoesNotExist()
    }
    @Test fun selectedLineUsesPlaylistNameAndMenuClosesAfterSelection() {
        val channel = Channel("c1", "新闻频道", "新闻", streams = listOf(first, second))
        val playlists = listOf(Playlist("p1", "主线路", "https://example.com/one"), Playlist("p2", "备用线路", "https://example.com/two"))
        val status = PlaybackStatus(channelId = channel.id, attempt = RouteAttempt(first.id, first.url, RouteTarget.DIRECT, "default"))
        var played: String? = null
        compose.setContent { MaterialTheme { ChannelDialog(channel, listOf(profile), playlists, status, {}, { _, _ -> }, { played = it }) } }
        compose.onNodeWithText("主线路").performClick()
        compose.onNodeWithText("备用线路").performClick()
        compose.onNodeWithText("备用线路").assertExists()
        compose.onNodeWithText("主线路").assertDoesNotExist()
        compose.onNodeWithText("private.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText("播放").performClick()
        compose.runOnIdle { assertEquals(second.id, played) }
    }
    @Test fun restoreAutoClearsSourceSelectionAndExplainsPrecedence() {
        val channel = Channel("c1", "新闻频道", "香港", streams = listOf(first.copy(manualRoute = RouteTarget.proxy(profile.id))), manualRoute = RouteTarget.DIRECT)
        val status = PlaybackStatus(channelId = channel.id, lastAttempt = RouteAttempt(first.id, first.url, RouteTarget.proxy(profile.id), "manual"))
        var restored = false
        var played: String? = "not played"
        compose.setContent { MaterialTheme { ChannelDialog(channel, listOf(profile), emptyList(), status, {}, { _, _ -> },
            { played = it }, onResetRoutes = { restored = true }) } }
        compose.onNodeWithText("首选节点：香港节点").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("高级设置").performScrollTo().performClick()
        compose.onNodeWithText("播放源代理优先于频道代理。手动节点为首选，失败后尝试同地区备用节点。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("恢复自动路由").performScrollTo().performClick()
        compose.onNodeWithText("自动选线").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("播放").performClick()
        compose.runOnIdle { assertTrue(restored); assertNull(played) }
    }

}
