package com.tvatlas.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistBundleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun existingBuiltInSourcesShowOneSubscriptionAndCustomSourceStaysSeparate() {
        val custom = Playlist("custom", "我的频道源", "https://example.com/channels.m3u")
        val library = Library(DefaultPlaylists.entries + custom, emptyList(), emptyList(), RuleConfig())
        var allRefreshed = false
        var customRefreshed = false
        compose.setContent { MaterialTheme { PlaylistPage(library, false, {}, { customRefreshed = it.id == custom.id }, { allRefreshed = true }) } }
        compose.onAllNodesWithText("TVAtlas 频道订阅").assertCountEquals(1)
        compose.onNodeWithText("TVB Plus 官方（实验）").assertDoesNotExist()
        compose.onNodeWithText("ViuTV 官方（实验）").assertDoesNotExist()
        compose.onNodeWithText("查看内置线路详情").performScrollTo().performClick()
        compose.onNodeWithText("内置订阅详情").assertIsDisplayed()
        compose.onNodeWithText("翡翠台官方（实验）").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("刷新订阅").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(allRefreshed) }
        compose.onNodeWithText("我的频道源").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("刷新", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(customRefreshed) }
    }
}
