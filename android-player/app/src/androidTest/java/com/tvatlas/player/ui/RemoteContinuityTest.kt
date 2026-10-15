@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import com.tvatlas.core.routing.RuleConfig
import com.tvatlas.player.playback.PlaybackStatus
import com.tvatlas.player.storage.Library
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()
    @Test fun leavingPageAndHidingPanelRestoreChannelScrollAndRemoteFocus() {
        val channels = (1..40).map { Channel("c$it", "CCTV-$it", "中国大陆 · CCTV", streams = emptyList()) }
        val library = Library(emptyList(), channels, emptyList(), RuleConfig())
        var showing by mutableStateOf(true)
        var played: String? = null
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            val memory = rememberSaveableStateHolder()
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            Box(Modifier.requiredSize(600.dp, 500.dp)) {
                if (showing) memory.SaveableStateProvider("channel-browser") {
                    ChannelList(library, PlaybackStatus(), true, Modifier.fillMaxSize(), { played = it.id }, {}, {}, autoExpand = true)
                } else Text("其他页面或全屏画面")
            }
        } } }
        compose.onNodeWithTag("channel-list").performScrollToNode(hasTestTag("channel:c20"))
        compose.onNodeWithTag("channel:c20").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("channel:c20").assertIsFocused()
        compose.runOnIdle { showing = false }
        compose.onNodeWithText("其他页面或全屏画面").assertIsDisplayed()
        compose.runOnIdle { showing = true }
        compose.waitForIdle()
        compose.onNodeWithTag("channel:c20").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("channel:c20").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("c20", played) }
    }
    @Test fun routeHistoryShowsSubscriptionNodeAndControlledFailureReason() {
        val channel = Channel("c", "测试频道", "香港", streams = listOf(Stream("s", "https://fixture.test/live", "p")))
        val profile = ProxyProfile("node", "香港节点 A", ProxyType.HTTP, "127.0.0.1", 7890, subscriptionId = "sub")
        val library = Library(listOf(Playlist("p", "官方线路", "https://fixture.test/playlist")), listOf(channel), listOf(profile), RuleConfig())
        val now = System.currentTimeMillis()
        val observations = listOf(
            com.tvatlas.core.player.RouteObservation("c", "s", RouteTarget.proxy("node"), now - 2000, true),
            com.tvatlas.core.player.RouteObservation("c", "s", RouteTarget.proxy("node"), now - 1000, false, com.tvatlas.core.player.FailureKind.NETWORK))
        compose.setContent { AtlasTheme {
            Column(Modifier.requiredSize(720.dp, 420.dp).atlasBackdrop().testTag("route-record-preview").padding(24.dp)) {
                Text("测试频道 · 线路详情")
                RouteRecordSummary(channel, library, listOf(com.tvatlas.player.storage.SubscriptionRow("sub", "测试订阅", now, 1)), observations)
            }
        } }
        compose.onNodeWithText("官方线路 · 测试订阅 · 香港节点 A").assertIsDisplayed()
        compose.onNodeWithText("成功 1／失败 1 · 成功率 50% · 最近：网络连接失败").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("route-record-preview").captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "route-records.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas-next")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

}
