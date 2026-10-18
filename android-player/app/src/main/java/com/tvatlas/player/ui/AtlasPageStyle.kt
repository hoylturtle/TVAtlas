package com.tvatlas.player.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared non-player surfaces; live playback keeps its own overlay layout. */
internal object AtlasPageStyle {
    val corner = RoundedCornerShape(12.dp)
    val sidebarWidth = 176.dp
    val gap = 12.dp
}

@Composable internal fun AtlasCard(modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = AtlasPageStyle.corner, colors = colors, elevation = CardDefaults.cardElevation(0.dp), content = content)
}

@Composable internal fun AtlasCard(onClick: () -> Unit, modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    content: @Composable ColumnScope.() -> Unit) {
    Card(onClick, modifier, shape = AtlasPageStyle.corner, colors = colors,
        elevation = CardDefaults.cardElevation(0.dp), content = content)
}

@Composable internal fun AtlasActionButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TvTextButton(onClick, modifier, enabled,
        colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface), content = content)
}
