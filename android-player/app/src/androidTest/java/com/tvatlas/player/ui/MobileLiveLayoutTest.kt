package com.tvatlas.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.model.Channel
import com.tvatlas.core.model.Stream
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Library
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileLiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val error = "视频分片下载失败：" + "播放源或代理连接暂不可用。".repeat(40)
    private val diagnostics = "线路：香港节点；命中地区规则。\n".repeat(30)
    private val library = Library(emptyList(), (1..50).map {
        Channel("c$it", "频道$it", "香港 · 综合", streams = listOf(Stream("s$it", "https://fixture.test/live", "p")))
    }, emptyList(), RuleConfig())
    private var selected: String? = null

    private fun render(height: Int, failed: Boolean = true) {
        compose.setContent { CompositionLocalProvider(LocalTvMode provides false) {
            MaterialTheme(colorScheme = colors) { Surface(Modifier.size(320.dp, height.dp).testTag("mobile-failure-layout")) {
                MobileLiveLayout(true, Modifier.fillMaxSize(), picture = { modifier ->
                    PlayerPaneLayout(true, modifier, picture = { Text(if (failed) "播放未成功" else "正在播放", color = Color.White) },
                        controls = {
                            VideoControls(if (failed) "播放未成功" else "正在播放", "线路", "香港节点", true, !failed,
                                {}, {}, {}, canReplay = failed)
                            CompactPlaybackDetails(if (failed) error else null, diagnostics)
                        })
                }, channels = { ChannelList(library, PlaybackStatus(), false, it, { selected = it.id }, {}, {}) })
            } }
        } }
    }
    @Test fun failureKeepsChannelSpaceAndCanScrollToAnotherChannel() {
        render(600)
        compose.onNodeWithTag("mobile-channel-area").assertHeightIsAtLeast(320.dp)
        compose.onNodeWithText("搜索频道").performTextInput("频道")
        compose.onNodeWithTag("channel-list").performScrollToNode(hasText("频道40"))
        compose.onNodeWithText("频道40").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("c40", selected) }
        val bitmap = compose.onNodeWithTag("mobile-failure-layout").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "mobile-failure-layout.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun detailsAreScrollableWithoutResizingChannelAreaOnSmallScreen() {
        render(420)
        val before = compose.onNodeWithTag("mobile-channel-area").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("mobile-channel-area").assertHeightIsAtLeast(220.dp)
        compose.onNodeWithText("详情").performClick()
        compose.onNodeWithText("播放失败详情").assertIsDisplayed()
        compose.onNodeWithTag("playback-details-scroll").performTouchInput { swipeUp() }
        assertTrue(compose.onNodeWithTag("playback-details-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        compose.onNodeWithText("关闭").performClick()
        val after = compose.onNodeWithTag("mobile-channel-area").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.onNodeWithText("搜索频道").performTextInput("频道")
        compose.onNodeWithTag("channel-list").performScrollToNode(hasText("频道50"))
        compose.onNodeWithText("频道50").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("c50", selected) }
    }
    @Test fun normalPlaybackUsesTheSameSplit() {
        render(600, failed = false)
        compose.onNodeWithTag("mobile-channel-area").assertHeightIsAtLeast(320.dp)
        compose.onNodeWithText("停止").assertIsDisplayed()
        compose.onNodeWithText("搜索频道").assertIsDisplayed()
    }
}
