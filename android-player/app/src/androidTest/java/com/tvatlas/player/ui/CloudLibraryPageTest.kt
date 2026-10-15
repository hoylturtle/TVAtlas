@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.tvatlas.player.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tvatlas.core.cloud.*
import com.tvatlas.player.BuildConfig
import com.tvatlas.player.cloud.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudLibraryPageTest {
    @get:Rule val compose = createAndroidComposeRule<OrientationTestActivity>()
    @Test fun previewHasSeparatePackageAndEncryptedShareStorage() {
        org.junit.Assume.assumeTrue(BuildConfig.BUILD_TYPE == "preview")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".preview"))
        assertEquals("com.tvatlas.player.preview", context.packageName)
        val prefs = context.getSharedPreferences("cloud_shares", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val share = CloudShare(CloudProvider.PAN115, "sExampleShare", "aB12", "测试")
            val store = CloudShareStore(context); store.save(share)
            val raw = prefs.all.values.joinToString()
            assertFalse(raw.contains(share.shareId)); assertFalse(raw.contains(share.code)); assertFalse(raw.contains("测试"))
            assertEquals(share, CloudShareStore(context).items.value.single())
            assertFalse(com.tvatlas.player.storage.CredentialVault(context).snapshot().values.any { it.password.contains(share.shareId) })
            store.remove(share.key); assertTrue(store.items.value.isEmpty())
            prefs.edit().putString("library", "corrupted-record").commit()
            val damaged = CloudShareStore(context)
            assertTrue(damaged.damaged)
            assertThrows(IllegalArgumentException::class.java) { damaged.save(share) }
            assertEquals("corrupted-record", prefs.getString("library", null))
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun importPreviewAndDirectoryStateDoNotPromisePlayback() {
        val share = CloudShare(CloudProvider.PAN115, "sExampleShare", name = "测试合集")
        var state by mutableStateOf(CloudLibraryState(shares = listOf(share)))
        var entered = false
        var importRequest by mutableStateOf(0)
        compose.setContent { AtlasTheme {
            CloudLibraryPage(state, { _, _, _, done -> done() }, { state = state.copy(selected = it,
                folders = listOf(CloudFolder("0", "分享根目录")), files = listOf(CloudFile("folder", "第一季", true), CloudFile("video", "E01.mp4", false, 1024))) },
                { entered = true }, {}, {}, {}, {}, {}, importRequest)
        } }
        compose.onNodeWithText("浏览目录").performClick()
        compose.onNodeWithText("第一季").performClick(); compose.runOnIdle { assertTrue(entered) }
        compose.onNodeWithText("E01.mp4").performClick()
        compose.onNodeWithText("已识别视频文件。原生取流和账号授权尚未接入，本阶段不能在 App 内播放。").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { importRequest++ }
        compose.onNodeWithText("完整分享文字或链接").assertIsDisplayed()
    }
    @Test fun directorySearchAndVideoFilterKeepFoldersAccessible() {
        val share = CloudShare(CloudProvider.PAN115, "sExampleShare", name = "测试合集")
        compose.setContent { AtlasTheme {
            CloudLibraryPage(CloudLibraryState(selected = share, folders = listOf(CloudFolder("0", "分享根目录")),
                files = listOf(CloudFile("folder", "第一季", true), CloudFile("video", "E01.mp4", false), CloudFile("text", "notes.txt", false))),
                { _, _, _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("搜索当前已加载文件").performTextInput("E01")
        compose.onNodeWithText("E01.mp4").assertExists()
        compose.onNodeWithText("第一季").assertDoesNotExist()
        compose.onNodeWithText("清除").performClick()
        compose.onNodeWithText("只看视频和文件夹").performClick()
        compose.onNodeWithText("第一季").assertExists()
        compose.onNodeWithText("E01.mp4").assertExists()
        compose.onNodeWithText("notes.txt").assertDoesNotExist()
    }
    @Test fun authorizationStatusDoesNotPretendUserIsLoggedIn() {
        compose.setContent { AtlasTheme {
            CloudLibraryPage(CloudLibraryState(), { _, _, _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("账号授权").performClick()
        compose.onNodeWithText("官方账号授权 · 接入状态").assertIsDisplayed()
        compose.onNodeWithText("目前未登录任何网盘账号。打开官网登录不会自动授权 TVAtlas。").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("官方账号授权 · 接入状态").assertDoesNotExist()
    }
    @Test fun cloudImportStartsFromSourcesAndLibraryShowsSavedShares() {
        var imported = false
        compose.setContent { AtlasTheme {
            PlaylistPage(com.tvatlas.player.storage.Library(emptyList(), emptyList(), emptyList(), com.tvatlas.core.routing.RuleConfig()),
                false, {}, {}, {}, cloudShareCount = 2, onImportCloud = { imported = true })
        } }
        compose.onNodeWithText("已导入 2 个分享 · 在影视页浏览文件夹").assertExists()
        compose.onNodeWithText("导入网盘分享 / 手机扫码").performClick()
        compose.runOnIdle { assertTrue(imported) }
    }
    @Test fun tvCanEnterDirectoryWithRemoteAndCapturePreviewScreens() {
        val share = CloudShare(CloudProvider.PAN115, "sExampleShare", name = "测试电影合集")
        var layout by mutableStateOf(0)
        var entered = false
        val state = CloudLibraryState(shares = listOf(share), selected = share,
            folders = listOf(CloudFolder("0", "分享根目录")) + (1..5).map { CloudFolder("$it", "很长的电影合集目录名称 $it / Classic Movies Collection") },
            files = listOf(CloudFile("folder", "第一季", true), CloudFile("video", "E01.mp4", false, 1024)) +
                (2..20).map { CloudFile("video$it", "E$it.mp4", false, 1024) })
        compose.setContent { AtlasTheme {
            val tv = layout == 2
            CompositionLocalProvider(LocalTvMode provides tv) {
            val input = androidx.compose.ui.platform.LocalInputModeManager.current
            LaunchedEffect(tv) { if (tv) input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            Scaffold(Modifier.requiredSize(if (layout == 0) 360.dp else 960.dp, if (layout == 0) 780.dp else 540.dp).atlasBackdrop().testTag("cloud-preview"),
                containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = { if (!tv) AtlasAppBar({}, {}) }, bottomBar = { if (!tv) AtlasBottomNavigation(4) {} }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (tv) TvNavigation(4, {}, Modifier.fillMaxHeight())
                    Box(Modifier.weight(1f)) { CloudLibraryPage(state, { _, _, _, done -> done() }, {}, { entered = true }, {}, {}, {}, {}, {}) }
                }
            }
        } } }
        fun capture(name: String) {
            val image = compose.onNodeWithTag("cloud-preview").captureToImage().asAndroidBitmap()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // connectedAndroidTest uninstalls its target, removing app-specific external files.
            // MediaStore screenshots survive that cleanup, matching the stable UI test workflow.
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVAtlas-next")
            }
            val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        capture("cloud-phone.png")
        compose.runOnIdle { layout = 1 }
        compose.waitForIdle(); capture("cloud-landscape.png")
        compose.onNodeWithTag("cloud-browser").assertExists()
        compose.runOnIdle { layout = 2 }
        compose.waitForIdle(); capture("cloud-tv.png")
        compose.onNodeWithText("第一季").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("第一季").assertIsFocused()
        val browser = compose.onNodeWithTag("cloud-browser").fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("cloud-navigation").fetchSemanticsNode().boundsInRoot
        val files = compose.onNodeWithTag("cloud-files").fetchSemanticsNode().boundsInRoot
        assertTrue(navigation.right < browser.left)
        assertTrue(files.height > browser.height * .55f)
        compose.onNodeWithText("第一季").performKeyInput { pressKey(Key.DirectionLeft) }
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().any { it.boundsInRoot.center.x < browser.left })
        compose.onNodeWithText("第一季").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("第一季").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(entered) }
    }
}
