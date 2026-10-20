@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.vod.*
import com.tvatlas.player.R
import com.tvatlas.player.vod.UnifiedVodState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()
    private fun item(id: String, category: String) = UnifiedVod.merge(listOf(VodOffer("fixture", CatalogMovie(id, "示例${category} $id · 完整片名", category, emptyList(), updatedAt = 100)))).single()
    @Test fun televisionAndShortLandscapeKeepFilmNamesVisibleAndRemoteConfirmationWorks() {
        var short by mutableStateOf(false)
        var selected: String? = null
        val movies = (1..4).map { item("m$it", "电影") }
        val series = (1..4).map { item("s$it", "国产剧") }
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides true) {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            key(short) { Box(Modifier.requiredSize(if (short) 760.dp else 960.dp, if (short) 320.dp else 540.dp)) {
                HomeScreen(UnifiedVodState(movies), UnifiedVodState(series), emptyList(), emptyList(), null, false,
                    { movie, _ -> selected = movie.id }, {}, {}, {}, {})
            } }
        } } }
        for (compact in listOf(false, true)) {
            compose.runOnIdle { short = compact }
            compose.waitForIdle()
            compose.onNodeWithText("常看直播").assertIsDisplayed()
            compose.onNodeWithTag("home-scroll").performScrollToNode(hasText(movies.first().movie.name))
            val viewport = compose.onNodeWithTag("home-page").getUnclippedBoundsInRoot()
            val firstTitle = compose.onNodeWithTag("home-title:${movies.first().id}", useUnmergedTree = true)
            firstTitle.assertIsDisplayed()
            assertTrue("Title must fit beneath poster", firstTitle.getUnclippedBoundsInRoot().bottom <= viewport.bottom)
            compose.onNodeWithTag("home-movie:${movies.first().id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertEquals(movies.first().id, selected) }
            compose.onNodeWithTag("home-scroll").performScrollToNode(hasText("最新剧集"))
            compose.onNodeWithText("最新剧集").assertIsDisplayed()
            compose.onNodeWithTag("home-scroll").performScrollToNode(hasText(series.first().movie.name))
            compose.onNodeWithTag("home-title:${series.first().id}", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun captureHomeOnTelevisionPhoneAndShortLandscape() {
        var mode by mutableIntStateOf(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val poster = "android.resource://${context.packageName}/${R.drawable.home_backdrop}"
        val movies = (1..4).map { item("m$it", "电影").let { it.copy(movie = it.movie.copy(poster = poster)) } }
        val series = (1..4).map { item("s$it", "国产剧").let { it.copy(movie = it.movie.copy(poster = poster)) } }
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides (mode != 1)) {
            key(mode) {
                Box(Modifier.requiredSize(if (mode == 1) 360.dp else if (mode == 2) 840.dp else 960.dp,
                    if (mode == 1) 780.dp else if (mode == 2) 320.dp else 540.dp).testTag("home-preview")) {
                    Row(Modifier.fillMaxSize()) {
                        if (mode != 1) TvNavigation(5, {})
                        HomeScreen(UnifiedVodState(movies), UnifiedVodState(series), emptyList(), emptyList(), null, false, { _, _ -> }, {}, {}, {}, {})
                    }
                }
            }
        } } }
        val directory = File("/sdcard/Pictures/TVAtlas").apply { mkdirs() }
        for ((index, name) in listOf("home-tv.jpg", "home-phone.jpg", "home-short.jpg").withIndex()) {
            compose.runOnIdle { mode = index }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("home-primary").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            if (index != 1) compose.onNodeWithTag("home-movie:${movies.first().id}")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            val bitmap = compose.onNodeWithTag("home-preview").captureToImage().asAndroidBitmap()
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        }
    }
}
