package com.tvatlas.player.ui

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.ui.PlayerView

/** Hidden playback controls: arrows seek; visible controls retain normal focus navigation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VodPlayerView(context: Context, private val seek: (Long) -> Unit) : PlayerView(context) {
    private var heldSeekKey: Int? = null
    private var lastSeekAt = 0L
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return handlePlaybackKey(event) || super.dispatchKeyEvent(event)
    }

    /** Also called by the dialog window when Compose temporarily owns keyboard focus. */
    internal fun handlePlaybackKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && heldSeekKey == event.keyCode) {
            heldSeekKey = null
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            heldSeekKey == event.keyCode && event.keyCode in revealKeys) return true
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 30_000L
            KeyEvent.KEYCODE_MEDIA_REWIND -> -30_000L
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!isControllerFullyVisible) 10_000L else null
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!isControllerFullyVisible) -10_000L else null
            else -> null
        }
        if (delta != null && event.action == KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (event.repeatCount == 0 || now - lastSeekAt >= 180) { seek(delta); lastSeekAt = now }
            heldSeekKey = event.keyCode
            return true
        }
        if (!isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN && event.keyCode in revealKeys) {
            showController()
            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
            heldSeekKey = event.keyCode
            return true
        }
        return false
    }

    private val revealKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
}

/** A Dialog has its own window: the activity's input callback does not receive these keys. */
internal class VodPlaybackWindowCallback(
    private val original: android.view.Window.Callback,
    private val playerView: () -> VodPlayerView?,
    private val record: (KeyEvent, Boolean) -> Unit = { _, _ -> }
) : android.view.Window.Callback by original {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = playerView()?.handlePlaybackKey(event) == true || original.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) record(event, handled)
        return handled
    }
}
