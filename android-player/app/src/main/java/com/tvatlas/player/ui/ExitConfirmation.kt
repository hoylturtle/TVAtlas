package com.tvatlas.player.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable internal fun ExitConfirmation(onCancel: () -> Unit, onExit: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text("退出 TVAtlas？") },
        text = { Text("退出后将停止播放。") },
        confirmButton = { TvTextButton(onClick = onExit) { Text("退出 APP") } },
        dismissButton = { TvTextButton(onClick = onCancel) { Text("继续观看") } })
}
