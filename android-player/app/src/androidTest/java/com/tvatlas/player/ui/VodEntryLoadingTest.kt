package com.tvatlas.player.ui

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tvatlas.core.vod.VodFilter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VodEntryLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()

    @Test fun initialBrowseAndClearedSearchRunWhileNoMovieGridExists() {
        val calls = AtomicInteger()
        var submitted by mutableStateOf("")
        compose.setContent {
            AtlasTheme {
                val scroll = rememberLazyGridState()
                // Exactly like the real empty state, do not attach scroll to a LazyGrid.
                Text("暂无影片")
                VodBrowseOnEntry(VodFilter(), submitted, scroll) { calls.incrementAndGet() }
            }
        }
        compose.waitUntil(5000) { calls.get() == 1 }
        compose.runOnIdle { submitted = "测试片名" }
        compose.waitUntil(5000) { calls.get() == 2 }
        compose.runOnIdle { submitted = "" }
        compose.waitUntil(5000) { calls.get() == 3 }
        compose.runOnIdle { assertEquals(3, calls.get()) }
    }
}
