package com.tvatlas.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.tvatlas.player.ui.PlayerApp

class MainActivity : ComponentActivity() {
    private var firstRemoteKey = true
    private var inputFramePending = false
    private val model: PlayerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AtlasApplication
        app.startupDiagnostics.foreground(true)
        app.debugLog.event("INFO", "STARTUP", "activity onCreate restored=${savedInstanceState != null}")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val started = android.os.SystemClock.elapsedRealtime()
        val playerModel = model
        app.debugLog.event("INFO", "STARTUP", "view-model acquired elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                playerModel.onFirstFrame()
                app.debugLog.event("INFO", "STARTUP", "first composition frame elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            }
            PlayerApp(playerModel)
        }
        installInputDiagnostics()
    }
    private fun installInputDiagnostics() {
        val original = window.callback ?: return
        window.callback = object : android.view.Window.Callback by original {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                val started = android.os.SystemClock.uptimeMillis()
                val log = (application as AtlasApplication).debugLog
                if (firstRemoteKey && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    firstRemoteKey = false
                    log.event("INFO", "INPUT", "first key received code=${event.keyCode}")
                }
                val remote = event.keyCode in setOf(4, 19, 20, 21, 22, 23, 66, 82)
                if (remote && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val queued = (started - event.eventTime).coerceAtLeast(0)
                    if (queued >= 100) log.event("WARN", "INPUT_QUEUE", "keyCode=${event.keyCode} queuedMs=$queued repeat=${event.repeatCount}")
                    if (!inputFramePending) {
                        inputFramePending = true
                        val code = event.keyCode
                        window.decorView.postOnAnimation {
                            inputFramePending = false
                            val wait = android.os.SystemClock.uptimeMillis() - started
                            if (wait >= 100) log.event("WARN", "INPUT_FRAME", "keyCode=$code frameCallbackMs=$wait")
                        }
                    }
                }
                val handled = original.dispatchKeyEvent(event)
                val elapsed = android.os.SystemClock.uptimeMillis() - started
                if (elapsed >= 100) log.event("WARN", "INPUT_SLOW", "keyCode=${event.keyCode} action=${event.action} dispatchMs=$elapsed handled=$handled")
                return handled
            }
        }
    }
    override fun onStart() {
        super.onStart()
        model.setAppForeground(true)
        (application as AtlasApplication).startupDiagnostics.foreground(true)
        (application as AtlasApplication).debugLog.event("INFO", "LIFECYCLE", "activity started")
    }
    override fun onStop() {
        model.setAppForeground(false)
        (application as AtlasApplication).startupDiagnostics.foreground(false)
        (application as AtlasApplication).debugLog.event("INFO", "LIFECYCLE", "activity stopped changingConfiguration=$isChangingConfigurations")
        if (!isChangingConfigurations) model.stopPlayback()
        model.traffic.checkpoint()
        super.onStop()
    }
}
