package com.tvatlas.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.tvatlas.core.model.*
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Library
import com.tvatlas.core.routing.RuleConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalTestApi::class)
class UserFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun favoriteCountAndGroupExpansionDescribeVisibleChannels() {
        val channels = listOf("Alpha", "Beta").map { name -> Channel(name, name, "香港", streams = listOf(Stream(name, "https://example.com/$name", "p"))) }
        val library = Library(emptyList(), channels, emptyList(), RuleConfig())
        compose.setContent { MaterialTheme(colorScheme = colors) {
            ChannelList(library, PlaybackStatus(), false, Modifier.size(360.dp, 640.dp), {}, {}, {}, setOf("Alpha"))
        } }
        compose.onNodeWithText("收藏", substring = false).performClick()
        compose.onNodeWithText("1 个频道 / 共 2 个").assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("⌄  香港 · 综合").performClick()
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("›  香港 · 综合").performClick()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
    }
    @Test fun favoriteAndRecentFiltersPreserveChannelSelection() {
        val channels = listOf("Alpha", "Beta").map { name -> Channel(name, name, "香港", streams = listOf(Stream(name, "https://example.com/$name", "p"))) }
        val library = Library(emptyList(), channels, emptyList(), RuleConfig())
        var favorites by mutableStateOf(emptySet<String>())
        compose.setContent { MaterialTheme(colorScheme = colors) {
            ChannelList(library, PlaybackStatus(), false, Modifier.size(360.dp, 640.dp), {}, {}, {}, favorites,
                listOf("Beta", "Alpha"), { channel -> favorites = if (channel.id in favorites) favorites - channel.id else favorites + channel.id })
        } }
        compose.onNodeWithText("香港", substring = true).performClick()
        compose.onNodeWithContentDescription("收藏 Alpha").performClick()
        compose.onNodeWithText("收藏", substring = false).performClick()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("最近", substring = false).performClick()
        val beta = compose.onNodeWithText("Beta").fetchSemanticsNode().boundsInRoot
        val alpha = compose.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot
        assertTrue(beta.top < alpha.top)
    }
    @Test fun fullScreenNetworkDetailsRemainInOneCompactRow() {
        compose.setContent { MaterialTheme(colorScheme = colors) {
            ImmersivePlaybackLayout(false, false, Modifier.size(640.dp, 360.dp), picture = {}, controls = {
                VideoControls("stopped", "线路", "香港代理", true, false, {}, {}, {}, canReplay = true,
                    fullscreenLabel = "退出全屏", immersive = true, tail = { Text("120 ms · 1.2 MiB/s") })
            })
        } }
        compose.onNodeWithText("120 ms · 1.2 MiB/s").assertIsDisplayed()
        compose.onNodeWithText("重新播放").assertIsDisplayed()
        val height = compose.onNodeWithTag("playback-controls").fetchSemanticsNode().boundsInRoot.height
        assertTrue(height / compose.activity.resources.displayMetrics.density <= 96f)
    }
    @Test fun trackPickerShowsActualAvailabilityAndCanDisableSubtitles() {
        lateinit var player: ExoPlayer
        compose.runOnIdle { player = ExoPlayer.Builder(compose.activity).build() }
        try {
            compose.setContent { MaterialTheme(colorScheme = colors) { PlaybackTracks(player) } }
            compose.onNodeWithText("画质").performClick()
            compose.onNodeWithText("当前播放源没有提供可选画质").assertIsDisplayed()
            compose.onNodeWithTag("track-list").performScrollToNode(hasText("关闭字幕"))
            compose.onNodeWithText("关闭字幕").performClick()
            compose.runOnIdle { assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) }
            compose.onNodeWithText("关闭", substring = false).performClick()
        } finally { compose.runOnIdle { player.release() } }
    }
    @Test fun trackDialogSurvivesBottomBarAutoHide() {
        lateinit var player: ExoPlayer
        var open by mutableStateOf(false)
        compose.runOnIdle { player = ExoPlayer.Builder(compose.activity).build() }
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { MaterialTheme(colorScheme = colors) {
                ImmersivePlaybackLayout(true, false, Modifier.size(640.dp, 360.dp), picture = {}, controls = {
                    TvTextButton(onClick = { open = true }) { Text("画质") }
                })
                if (open) PlaybackTrackDialog(player) { open = false }
            } }
            compose.onNodeWithText("画质").performClick()
            compose.mainClock.advanceTimeBy(10000)
            compose.onNodeWithTag("playback-controls").assertDoesNotExist()
            compose.onNodeWithText("画质、音轨与字幕").assertIsDisplayed()
            compose.onNodeWithText("关闭", substring = false).performClick()
        } finally { compose.runOnIdle { player.release() } }
    }
    @Test fun androidXmlParserUsesCurrentAndNextProgrammeTimes() {
        val guide = com.tvatlas.core.epg.XmlTv.parse("""<tv><channel id="Jade.hk"><display-name>翡翠台</display-name></channel><programme channel="Jade.hk" start="20261013120000 +0800" stop="20261013123000 +0800"><title>新闻</title></programme></tv>""".toByteArray())
        assertEquals(1, guide.programmes.size)
        assertEquals(1800000L, guide.programmes.first().end - guide.programmes.first().start)
    }
}
