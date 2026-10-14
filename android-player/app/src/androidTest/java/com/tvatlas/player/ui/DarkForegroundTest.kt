package com.tvatlas.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Reproduce the activity default black foreground without a masking opaque Surface. */
class DarkForegroundTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transparentPageAndChannelOverlayUseLightForeground() {
        var page = Color.Unspecified
        var overlay = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                AtlasTheme {
                    Scaffold(containerColor = Color.Transparent) { _ ->
                        page = LocalContentColor.current
                        TvBrowseLayout(false, null, channels = {
                            overlay = LocalContentColor.current
                            Text("频道数量")
                        }, picture = { Box(androidx.compose.ui.Modifier) })
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(colors.onSurface, page)
            assertEquals(colors.onSurface, overlay)
        }
    }
}
