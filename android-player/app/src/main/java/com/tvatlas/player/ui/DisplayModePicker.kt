package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvatlas.player.storage.DisplayMode

@Composable internal fun DisplayModePicker(selected: DisplayMode, onSelect: (DisplayMode) -> Unit) {
    Text("界面模式", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayMode.entries.forEach { mode ->
            TvTextButton(onClick = { onSelect(mode) }, modifier = Modifier.selectionOutline(mode == selected)) { Text(mode.label) }
        }
    }
    Text("电视盒子未被系统识别时可手动选择电视布局，立即生效并保存。此选项只调整界面，不改变移动数据保护。", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
}
