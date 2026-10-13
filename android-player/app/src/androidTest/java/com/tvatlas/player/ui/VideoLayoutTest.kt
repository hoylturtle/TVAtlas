package com.tvatlas.player.ui
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
class VideoLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val longName = "🇭🇰 ②香港 | HKG [解锁] x1.0{VerPL6}".repeat(5)
    @Test fun normalControlsExposeMetricsAndSubscriptionNodeWithoutFullscreen() {
        compose.setContent {
            MaterialTheme {
                VideoControls("正在播放", "官方线路", "艾可云 · 香港 2", true, true, {}, {}, {},
                    tail = { androidx.compose.material3.Text("延迟 25 ms · 网速 1 MB/s") })
            }
        }
        compose.onNodeWithText("延迟 25 ms · 网速 1 MB/s").assertIsDisplayed()
        compose.onNodeWithText("艾可云 · 香港 2").assertIsDisplayed()
    }
    @Test fun portraitPictureKeeps169AndActionsRemainVisibleWithLongNames() {
        var fullscreen = false
        var stopped = false
        compose.setContent {
            MaterialTheme {
                PlayerPaneLayout(false, Modifier.width(320.dp),
                    picture = { Box(Modifier.fillMaxSize().testTag("picture")) },
                    controls = { VideoControls("翡翠台正在播放", "翡翠台官方（实验）".repeat(5), longName,
                        true, true, {}, { fullscreen = true }, { stopped = true }) })
            }
        }
        compose.onNodeWithTag("picture").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(180.dp)
        compose.onNodeWithText("全屏 / 频道").assertIsDisplayed().performClick()
        compose.onNodeWithText("停止").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(fullscreen); assertTrue(stopped) }
    }
    @Test fun fullscreenPictureFillsRemainingSpaceWithLongProxyName() {
        compose.setContent {
            MaterialTheme {
                PlayerPaneLayout(true, Modifier.size(320.dp, 360.dp),
                    picture = { Box(Modifier.fillMaxSize().testTag("picture")) },
                    controls = { VideoControls("正在播放", "线路", longName, true, true, {}, {}, {}) })
            }
        }
        compose.onNodeWithTag("picture").assertWidthIsEqualTo(320.dp).assertHeightIsAtLeast(240.dp)
        compose.onNodeWithText("全屏 / 频道").assertIsDisplayed()
        compose.onNodeWithText("停止").assertIsDisplayed()
    }
}
