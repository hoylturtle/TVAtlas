package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A visible focus ring independent of the subtle Material touch state layer. */
internal fun Modifier.tvFocusOutline(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp,
            if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
        .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else Color.Transparent,
            RoundedCornerShape(12.dp))
}


/** Selection uses a purple fill; only actual TV remote focus has an outline. */
internal fun Modifier.selectionOutline(selected: Boolean): Modifier = composed {
    val context = LocalContext.current
    val tv = LocalTvMode.current ?: ((context.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .background(if (selected || focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
        .border(if (tv && focused) 3.dp else 0.dp,
            if (tv && focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
}

@Composable internal fun TvNavigation(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(132.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AtlasBrand(Modifier.padding(vertical = 16.dp))
        listOf("直播", "频道源", "代理", "设置").forEachIndexed { index, label ->
            TvTextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectionOutline(selected == index),
                colors = ButtonDefaults.textButtonColors(containerColor = if (selected == index)
                    MaterialTheme.colorScheme.primary else Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)) {
                AppNavigationIcon(index)
                Spacer(Modifier.width(6.dp))
                Text(label, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** Playback remains full size; navigation and channels float above it. */
@Composable internal fun TvBrowseLayout(tv: Boolean, channelName: String?,
    channels: @Composable () -> Unit, picture: @Composable () -> Unit,
    navigation: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()) { picture() }
        Box(Modifier.align(androidx.compose.ui.Alignment.CenterStart).fillMaxHeight().padding(bottom = 76.dp)) { navigation() }
        Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
            .fillMaxHeight().width(if (tv) 340.dp else 320.dp).padding(top = 12.dp, bottom = 76.dp, end = 12.dp),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.68f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Text("频道列表", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("方向键选择 · 确认播放 · 返回收起", Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) { channels() }
            }
        }
    }
}
