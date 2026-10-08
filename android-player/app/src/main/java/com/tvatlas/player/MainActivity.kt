package com.tvatlas.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.tvatlas.player.ui.PlayerApp

class MainActivity : ComponentActivity() {
    private val model: PlayerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { PlayerApp(model) }
    }
    override fun onStop() {
        model.playback.stop(false)
        super.onStop()
    }
}
