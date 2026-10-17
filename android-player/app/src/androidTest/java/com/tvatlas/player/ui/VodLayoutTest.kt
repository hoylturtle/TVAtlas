@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tvatlas.core.vod.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VodLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()
    private val movies = (0..11).map { CatalogMovie("m$it", "示例影片 $it", "电影", emptyList()) }
    @Test fun filterDialogAppliesGenreRegionAndYearTogether() {
        var selected: VodFilter? = null
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides false) {
            VodFilterDialog(VodFilter(), {}, { selected = it })
        } } }
        compose.onNodeWithText("动作片").performClick()
        compose.onNodeWithText("香港").performScrollTo().performClick()
        compose.onNodeWithText("2024").performScrollTo().performClick()
        compose.onNodeWithText("应用筛选").performClick()
        compose.runOnIdle { assertEquals(VodFilter(VodKind.MOVIE, "动作片", "香港", "2024"), selected) }
    }
    @Test fun phoneUsesTwoColumnsWithSeparateRows() {
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides false) {
            Box(Modifier.requiredSize(360.dp, 700.dp)) { VodMovieGrid(movies, null, false, Modifier.fillMaxSize(), onSelect = {}) }
        } } }
        val cards = (0..3).map { compose.onNodeWithTag("vod-movie:m$it").getUnclippedBoundsInRoot() }
        assertEquals(2, cards.map { it.left }.distinct().size)
        assertTrue(cards[0].right < cards[1].left)
        assertTrue(cards[0].bottom < cards[2].top)
    }
    @Test fun televisionUsesMultipleColumnsAndRemoteConfirmation() {
        var selected: String? = null
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            Box(Modifier.requiredSize(1000.dp, 700.dp)) { VodMovieGrid(movies, null, false, Modifier.fillMaxSize(), onSelect = { selected = it.id }) }
        } } }
        val cards = (0..5).map { compose.onNodeWithTag("vod-movie:m$it").getUnclippedBoundsInRoot() }
        assertTrue(cards.map { it.left }.distinct().size >= 5)
        compose.onNodeWithTag("vod-movie:m0").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("vod-movie:m0").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("m0", selected) }
    }
    @Test fun episodesAreMergedAndRawLineSelectorsAreHidden() {
        val first = CatalogEpisode("第01集", "https://fixture.invalid/a.m3u8?key=1", "raw_a")
        val alternate = CatalogEpisode("EP1", "https://fixture.invalid/b.mp4", "raw_b")
        val movie = CatalogMovie("film", "示例影片", "电影", listOf(first, alternate))
        var selected: VodEpisodeChoice? = null
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides false) {
            Box(Modifier.requiredSize(360.dp, 800.dp)) { VodMovieDetails(movie, null, false, false, Modifier.fillMaxSize(), { selected = it }) }
        } } }
        compose.onNodeWithText("播放线路").assertDoesNotExist()
        compose.onNodeWithText("raw_a").assertDoesNotExist()
        compose.onNodeWithTag("vod-episode:0").performClick()
        compose.runOnIdle { assertEquals(listOf(first, alternate), selected!!.candidates) }
    }
}
