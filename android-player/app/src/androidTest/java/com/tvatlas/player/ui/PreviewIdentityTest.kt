package com.tvatlas.player.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.player.BuildConfig
import com.tvatlas.player.storage.*
import com.tvatlas.player.update.UpdateState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()
    @Test fun installedApkAndVisibleBuildIdentityAgree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("preview", BuildConfig.BUILD_TYPE)
        assertTrue(BuildConfig.VERSION_CODE >= 1000000)
        assertTrue(BuildConfig.VERSION_NAME.contains("preview.${BuildConfig.PREVIEW_ID}"))
        assertEquals(BuildConfig.VERSION_NAME, info.versionName)
        @Suppress("DEPRECATION")
        assertEquals(BuildConfig.VERSION_CODE, info.versionCode)
        assertTrue(context.applicationInfo.loadLabel(context.packageManager).toString().contains(BuildConfig.PREVIEW_ID))
        compose.setContent { AtlasTheme { AtlasBrand() } }
        compose.onNodeWithText("预览 #${BuildConfig.PREVIEW_ID}").assertIsDisplayed()
    }
    @Test fun phoneReportedDeviceCanPersistAndReverseTelevisionLayoutChoice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val previous = store.options.first()
        store.editOptions { it.copy(displayMode = DisplayMode.AUTO) }
        try {
            compose.setContent {
                val options by store.options.collectAsState(initial = UserOptions())
                val scope = rememberCoroutineScope()
                val television = options.displayMode.television(false)
                AtlasTheme { CompositionLocalProvider(LocalTvMode provides television) {
                    Row(Modifier.requiredSize(1100.dp, 720.dp)) {
                        if (television) Box(Modifier.testTag("forced-tv-nav")) { TvNavigation(3, {}) }
                        Box(Modifier.weight(1f)) {
                            SettingsPage(false, {}, UpdateState(), {}, {}, interfaceContent = {
                                DisplayModePicker(options.displayMode) { mode -> scope.launch { store.editOptions { it.copy(displayMode = mode) } } }
                            })
                        }
                    }
                } }
            }
            compose.onNodeWithText("界面与遥控器").performClick()
            compose.onNodeWithText("电视", substring = false).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("forced-tv-nav").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(DisplayMode.TV, SettingsStore(context).options.first().displayMode)
            compose.onAllNodesWithText("界面与遥控器").onFirst().performClick()
            compose.onNodeWithText("手机／平板", substring = false).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("forced-tv-nav").fetchSemanticsNodes().isEmpty() }
            assertEquals(DisplayMode.TOUCH, SettingsStore(context).options.first().displayMode)
        } finally { store.editOptions { previous } }
    }
    @Test fun previewUpdateScreenDisplaysVersionAndLayout() {
        compose.setContent { AtlasTheme { CompositionLocalProvider(LocalTvMode provides false) {
            Column(Modifier.requiredSize(960.dp, 600.dp).atlasBackdrop().testTag("preview-version-screen").padding(16.dp)) {
                AtlasBrand()
                SettingsPage(false, {}, UpdateState(), {}, {})
            }
        } } }
        compose.onNodeWithText("更新与诊断").performClick()
        compose.onNodeWithText("版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）").assertIsDisplayed()
        compose.onNodeWithText("当前布局识别：手机／平板").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("preview-version-screen").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "preview-version.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas-next")
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

}
