package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
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

/** Card/row focus gets a filled overlay, so Material surfaces cannot paint over the indication. */
internal fun Modifier.tvFocusOutline(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.hasFocus }.drawWithContent {
        drawContent()
        if (focused) drawRoundRect(color.copy(alpha = .34f), cornerRadius = CornerRadius(12.dp.toPx()))
    }.border(if (focused) 2.dp else 0.dp, if (focused) color else Color.Transparent, RoundedCornerShape(12.dp))
}

/** Committed selection is a leading marker; the full purple fill belongs exclusively to focus. */
internal fun Modifier.selectionOutline(selected: Boolean): Modifier = composed {
    val color = MaterialTheme.colorScheme.primary
    val selectedOnFocus = MaterialTheme.colorScheme.onPrimary
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }.semantics { this.selected = selected }.drawWithContent {
        drawContent()
        if (focused) drawRoundRect(color.copy(alpha = .34f), cornerRadius = CornerRadius(12.dp.toPx()))
        if (selected) drawRoundRect(if (focused) selectedOnFocus else color,
            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height), cornerRadius = CornerRadius(2.dp.toPx()))
    }
}

@Composable internal fun TvNavigation(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, requestSelectedFocus: Boolean = false) {
    val selectedFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(requestSelectedFocus) {
        if (requestSelectedFocus) { withFrameNanos { }; selectedFocus.requestFocus() }
    }
    Column(modifier.width(132.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)).padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AtlasBrand(Modifier.padding(vertical = 16.dp))
        atlasNavigationItems.forEach { (index, label) ->
            TvTextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("main-nav:$index").then(if (selected == index) Modifier.focusRequester(selectedFocus) else Modifier).selectionOutline(selected == index),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                AppNavigationIcon(index)
                Spacer(Modifier.width(6.dp))
                Text(label, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

internal fun Modifier.liveFocusOutline(): Modifier = tvFocusOutline()

internal val LocalLiveOverlay = staticCompositionLocalOf { false }

internal val LocalLiveGuideOpen = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

/** Playback remains full size; navigation and channels float above it. */
@Composable internal fun TvBrowseLayout(tv: Boolean, channelName: String?,
    channels: @Composable () -> Unit, picture: @Composable () -> Unit,
    navigation: @Composable (() -> Unit) -> Unit = {}, onNavigation: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        var guideExpanded by remember { mutableStateOf(false) }
        var navigationOpen by remember { mutableStateOf(false) }
        val browserWidth = if (guideExpanded) (maxWidth * .56f).coerceIn(620.dp, 900.dp).coerceAtMost(maxWidth)
            else (maxWidth * .36f).coerceIn(360.dp, 560.dp).coerceAtMost(maxWidth)
        Box(Modifier.fillMaxSize()) { picture() }

        if (!navigationOpen) Surface(Modifier.align(androidx.compose.ui.Alignment.CenterStart)
            .fillMaxHeight().width(browserWidth).padding(bottom = 64.dp),
            color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF11121D).copy(alpha = .84f), Color(0xFF11121D).copy(alpha = .72f), Color(0xFF11121D).copy(alpha = .50f))))) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AtlasBrand()
                    Text("  |  直播", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TvIconButton(onClick = { if (onNavigation != null) onNavigation() else navigationOpen = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "导航") }
                }
                Box(Modifier.weight(1f).testTag("live-channel-overlay")) {
                    CompositionLocalProvider(LocalLiveOverlay provides true, LocalLiveGuideOpen provides { guideExpanded = it }) { channels() }
                }
            }
        }
        if (navigationOpen) LiveNavigationDrawer(onDismiss = { navigationOpen = false }) { dismiss -> navigation(dismiss) }
    }
}

/** A left-edge modal drawer, never a narrow dialog centered over the channel list. */
@Composable internal fun LiveNavigationDrawer(onDismiss: () -> Unit, onExit: (() -> Unit)? = null, focusReturn: Boolean = true, navigation: @Composable (() -> Unit) -> Unit) {
    val returnFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().testTag("live-navigation-window")) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).clickable(onClick = onDismiss))
            Surface(Modifier.align(Alignment.CenterStart).widthIn(max = 260.dp).fillMaxHeight().testTag("live-navigation-drawer"),
                color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.fillMaxSize()) {
                    TvTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(12.dp)
                        .then(Modifier.focusRequester(returnFocus))) { Text("返回直播") }
                    LaunchedEffect(focusReturn) { if (focusReturn) { withFrameNanos { }; returnFocus.requestFocus() } }
                    Box(Modifier.weight(1f).fillMaxWidth()) { navigation(onDismiss) }
                    if (onExit != null) TvTextButton(onClick = onExit, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("退出应用") }
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
