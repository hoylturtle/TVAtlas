package com.tvatlas.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable internal fun atlasTv(): Boolean {
    val context = LocalContext.current
    return LocalTvMode.current ?: remember(context) {
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
internal fun Modifier.atlasBackdrop(): Modifier = background(Brush.linearGradient(listOf(
    Color(0xFF11101C), Color(0xFF1D172B), Color(0xFF10101A))))

@Composable internal fun AtlasBrand(modifier: Modifier = Modifier) {
    Text(buildAnnotatedString { append("TV"); withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Atlas") } },
        modifier, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}
@Composable internal fun AtlasHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
@Composable internal fun AtlasPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp))
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.surface)))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** Small painted label, with a separate 48dp touch/remote target. */
@Composable internal fun AtlasChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = atlasTv()
    var focused by remember { mutableStateOf(false) }
    TextButton(onClick, modifier.heightIn(min = 48.dp).semantics { this.selected = selected }
        .onFocusChanged { focused = it.hasFocus }
        .border(if (tv && focused) 2.dp else 0.dp, if (tv && focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(label, Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .6f))
            .padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}
@Composable internal fun AtlasTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label -> AtlasChip(label, selected == index, { onSelect(index) }) }
    }
}
@Composable internal fun AtlasKeyRow(key: String, title: String, description: String) {
    Row(Modifier.fillMaxWidth().then(if (atlasTv()) Modifier.tvFocusOutline().focusable() else Modifier).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.width(74.dp).heightIn(min = 36.dp).padding(6.dp), contentAlignment = Alignment.Center) {
                Text(key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun AtlasSettingAction(title: String, summary: String, value: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    TvTextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(" ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun AtlasBottomNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("直播", "频道源", "代理", "设置").forEachIndexed { index, label ->
                TextButton(onClick = { onSelect(index) }, modifier = Modifier.weight(1f).fillMaxHeight()
                    .semantics { this.selected = selected == index }, contentPadding = PaddingValues(2.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.width(44.dp).height(28.dp).clip(RoundedCornerShape(9.dp))
                            .background(if (selected == index) MaterialTheme.colorScheme.primary.copy(alpha = .2f) else Color.Transparent), contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides if (selected == index) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) { AppNavigationIcon(index) }
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = if (selected == index) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun AtlasIdlePicture(message: String, channel: com.tvatlas.core.model.Channel? = null, loading: Boolean = false) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2C1C4A), Color(0xFF08080D)))), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            channel?.let { ChannelArtwork(it); Text(it.name, style = MaterialTheme.typography.titleMedium, color = Color.White) }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD2CADF), maxLines = 3)
        }
    }
}

@Composable internal fun AtlasAppBar(onSearch: () -> Unit, onSettings: () -> Unit, busy: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AtlasBrand(Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        IconButton(onSearch) { Icon(androidx.compose.material.icons.Icons.Default.Search, "搜索频道") }
        IconButton(onSettings) { Icon(androidx.compose.material.icons.Icons.Default.Settings, "打开设置") }
    }
}
