@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.tvatlas.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Force either landscape side even with auto-rotate disabled; restore the caller's policy on exit. */
@Composable internal fun PlaybackOrientation(immersive: Boolean, tv: Boolean) {
    val activity = LocalContext.current.activity()
    var previous by rememberSaveable { mutableStateOf<Int?>(null) }
    val locked = immersive && !tv
    DisposableEffect(activity, locked) {
        if (locked && activity != null) {
            if (previous == null) previous = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (locked && activity != null) {
                previous?.let { activity.requestedOrientation = it }
                previous = null
            }
        }
    }
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
        val decor = activity?.window?.decorView
        val reapply = Runnable { apply() }
        decor?.post(reapply)
        val focusObserver = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) apply() }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focusObserver)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) apply() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            decor?.removeCallbacks(reapply)
            decor?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusObserver)
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
    var controlsRequest by remember { mutableIntStateOf(0) }
    var controlsFocused by remember { mutableStateOf(false) }
    val pictureFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val shown = visible || !playing
    LaunchedEffect(playing, visible, controlsFocused, contentKey) {
        if (playing && visible && !controlsFocused) { delay(5000); visible = false }
    }
    LaunchedEffect(shown, tv, controlsRequest, playing) {
        if (shown && tv && (controlsRequest > 0 || !playing)) controlsFocus.requestFocus()
        else pictureFocus.requestFocus()
    }
    Box(modifier.background(Color.Black).testTag("immersive-player")) {
        Box(Modifier.fillMaxSize().focusRequester(pictureFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.nativeKeyEvent.isLongPress) onDetails()
                        else { visible = true; controlsRequest++ }
                        true
                    }
                    Key.DirectionUp -> { onChannel(-1); true }
                    Key.DirectionDown -> { onChannel(1); true }
                    Key.DirectionLeft -> { visible = true; controlsRequest++; true }
                    else -> false
                }
            }.focusable().clickable { visible = !shown }, contentAlignment = Alignment.Center) { picture() }
        if (shown) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.28f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onPreviewKeyEvent { event ->
                if (tv && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && playing) {
                    visible = false; pictureFocus.requestFocus(); true
                } else false
            }
            .focusRequester(controlsFocus).onFocusChanged { controlsFocused = tv && it.hasFocus }.focusGroup()
            .testTag("playback-controls")) { controls() }
    }
}
