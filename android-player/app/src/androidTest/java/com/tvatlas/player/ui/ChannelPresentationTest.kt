package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Library
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChannelPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val first = Stream("s1", "https://private.example/stream?token=fixture", "p1")
    private val second = Stream("s2", "https://private.example/other?token=fixture", "p2")
    private val profile = ProxyProfile("sub_internal_identifier", "香港节点", ProxyType.MIHOMO, "private.example", 443)

    @Test fun groupsStartCollapsedAndSearchShowsMatchesWithoutOpeningEveryGroup() {
        val channels = listOf(Channel("c1", "CCTV 新闻", "新闻", streams = listOf(first)), Channel("c2", "体育频道", "体育", streams = listOf(second)))
        val library = Library(emptyList(), channels, emptyList(), RuleConfig())
        compose.setContent { MaterialTheme { ChannelList(library, PlaybackStatus(), false, Modifier, {}, {}, {}) } }
        compose.onNodeWithText("CCTV 新闻").assertDoesNotExist()
        compose.onNodeWithText("体育频道").assertDoesNotExist()
        compose.onNodeWithText("›  新闻").performClick()
        compose.onNodeWithText("CCTV 新闻").assertExists()
        compose.onNodeWithText("⌄  新闻").performClick()
        compose.onNodeWithText("CCTV 新闻").assertDoesNotExist()
        compose.onNodeWithText("搜索频道").performTextInput("体育频道")
        compose.onNodeWithText("体育频道").assertExists()
        compose.onNodeWithText("搜索频道").performTextClearance()
        compose.onNodeWithText("体育频道").assertDoesNotExist()
    }
    @Test fun tvInitiallyFocusesCollapsedGroupAndEnterExpandsIt() {
        val channel = Channel("c1", "新闻频道", "新闻", streams = listOf(first))
        val library = Library(emptyList(), listOf(channel), emptyList(), RuleConfig())
        compose.setContent { MaterialTheme { ChannelList(library, PlaybackStatus(), true, Modifier, {}, {}, {}) } }
        compose.onNodeWithText("›  新闻").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("新闻频道").assertExists()
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
}
