package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A visible focus ring independent of the subtle Material touch state layer. */
internal fun Modifier.tvFocusOutline(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp,
            if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
        .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
            RoundedCornerShape(12.dp))
}

@Composable internal fun TvNavigation(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(112.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("TVAtlas", Modifier.padding(vertical = 20.dp), fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium)
        listOf("直播", "播放列表", "路由", "设置").forEachIndexed { index, label ->
            TextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).tvFocusOutline(),
                colors = ButtonDefaults.textButtonColors(containerColor = if (selected == index)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)) {
                Text(label, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("方向键移动\n确认选择", color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelMedium)
    }
}
