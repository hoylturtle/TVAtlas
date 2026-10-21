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
        if (event.action == KeyEvent.ACTION_UP && heldSeekKey == event.keyCode) {
            heldSeekKey = null
            return true
        }
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
        return super.dispatchKeyEvent(event)
    }
}
