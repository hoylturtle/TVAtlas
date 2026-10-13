@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.tvatlas.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Scope system UI changes to the playback screen, including return from a dialog/background. */
@Composable internal fun PlaybackSystemBars(immersive: Boolean) {
    val activity = LocalContext.current.activity()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(activity, owner, immersive) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        fun apply() {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) controller?.hide(WindowInsetsCompat.Type.systemBars())
            else controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        apply()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) apply() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Controls float over a full-size viewport, never shrinking the video. */
@Composable internal fun ImmersivePlaybackLayout(
    playing: Boolean, tv: Boolean, modifier: Modifier = Modifier, contentKey: Any? = null,
    onChannel: (Int) -> Unit = {}, onDetails: () -> Unit = {},
    picture: @Composable () -> Unit, controls: @Composable () -> Unit,
) {
    var visible by remember(contentKey) { mutableStateOf(true) }
    var focusControls by remember { mutableStateOf(false) }
    var controlsFocused by remember { mutableStateOf(false) }
    val pictureFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val shown = visible || !playing
    LaunchedEffect(playing, visible, controlsFocused, contentKey) {
        if (playing && visible && !controlsFocused) { delay(5000); visible = false }
    }
    LaunchedEffect(shown, tv, focusControls, playing) {
        if (shown && tv && (focusControls || !playing)) controlsFocus.requestFocus()
        else pictureFocus.requestFocus()
    }
    Box(modifier.background(Color.Black).testTag("immersive-player")) {
        Box(Modifier.fillMaxSize().focusRequester(pictureFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.nativeKeyEvent.isLongPress) onDetails()
                        else { visible = true; focusControls = tv }
                        true
                    }
                    Key.DirectionUp -> { onChannel(-1); true }
                    Key.DirectionDown -> { onChannel(1); true }
                    Key.DirectionLeft -> { visible = true; focusControls = tv; true }
                    else -> false
                }
            }.focusable().clickable { visible = !shown }, contentAlignment = Alignment.Center) { picture() }
        if (shown) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(controlsFocus).onFocusChanged { controlsFocused = tv && it.hasFocus }.focusGroup()
            .testTag("playback-controls")) { controls() }
    }
}
