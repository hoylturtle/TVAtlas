package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.PlayArrow

/** A visible focus ring independent of the subtle Material touch state layer. */
internal fun Modifier.tvFocusOutline(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp,
            if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))

}


/** Selection uses a purple fill; only actual TV remote focus has an outline. */
internal fun Modifier.selectionOutline(selected: Boolean): Modifier = composed {
    val context = LocalContext.current
    val tv = LocalTvMode.current ?: ((context.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .semantics { this.selected = selected }
        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
        .border(if (tv && focused) 3.dp else 0.dp,
            if (tv && focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
}

@Composable internal fun TvNavigation(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(132.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)).padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AtlasBrand(Modifier.padding(vertical = 16.dp))
        atlasNavigationItems.forEach { (index, label) ->
            TvTextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("main-nav:$index").selectionOutline(selected == index),
                colors = ButtonDefaults.textButtonColors(containerColor = if (selected == index)
                    MaterialTheme.colorScheme.primary else Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)) {
                AppNavigationIcon(index)
                Spacer(Modifier.width(6.dp))
                Text(label, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

internal fun Modifier.liveFocusOutline(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
}

internal val LocalLiveOverlay = staticCompositionLocalOf { false }

internal val LocalLiveGuideOpen = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

/** Playback remains full size; navigation and channels float above it. */
@Composable internal fun TvBrowseLayout(tv: Boolean, channelName: String?,
    channels: @Composable () -> Unit, picture: @Composable () -> Unit,
    navigation: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        var guideExpanded by remember { mutableStateOf(false) }
        val browserWidth = if (guideExpanded) (maxWidth * .56f).coerceIn(620.dp, 900.dp).coerceAtMost(maxWidth)
            else (maxWidth * .36f).coerceIn(360.dp, 560.dp).coerceAtMost(maxWidth)
        Box(Modifier.fillMaxSize()) { picture() }

        Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
            .fillMaxHeight().width(browserWidth).padding(bottom = 64.dp),
            color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF11121D).copy(alpha = .84f), Color(0xFF11121D).copy(alpha = .72f), Color(0xFF11121D).copy(alpha = .50f))))) {
                var navigationOpen by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AtlasBrand()
                    Text("  |  直播", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { navigationOpen = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "导航") }
                }
                if (navigationOpen) androidx.compose.ui.window.Dialog(onDismissRequest = { navigationOpen = false }) {
                    Surface { Column { TvTextButton(onClick = { navigationOpen = false }) { Text("返回直播") }; navigation() } }
                }
                Box(Modifier.weight(1f).testTag("live-channel-overlay")) {
                    CompositionLocalProvider(LocalLiveOverlay provides true, LocalLiveGuideOpen provides { guideExpanded = it }) { channels() }
                }
            }
        }
    }
}

/** Visible top-level navigation lives outside this home surface; stopped video never covers it. */
@Composable internal fun LiveHomeLayout(channelName: String?, message: String, onReplay: () -> Unit,
    channels: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("live-home")) {
        val showSummary = maxWidth >= 800.dp
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("直播频道", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (channelName != null) TvTextButton(onClick = onReplay) { Text("重新播放") }
                }
                Box(Modifier.weight(1f)) { channels() }
            }
            if (showSummary) Column(Modifier.width(260.dp).fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(androidx.compose.material.icons.Icons.Default.PlayArrow, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(channelName ?: "选择频道开始观看", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun MainNavigationRail(wide: Boolean, immersive: Boolean, activePlayback: Boolean,
    selected: Int, onSelect: (Int) -> Unit) {
    if (wide && !immersive && (selected != 0 || !activePlayback)) {
        CompositionLocalProvider(LocalTvMode provides true) {
            TvNavigation(selected, onSelect, Modifier.fillMaxHeight().testTag("main-navigation"))
        }
    }
}
