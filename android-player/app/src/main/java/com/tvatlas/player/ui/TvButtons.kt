package com.tvatlas.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val LocalTvMode = staticCompositionLocalOf<Boolean?> { null }
@Composable private fun isTv(): Boolean {
    val context = LocalContext.current
    return LocalTvMode.current ?: remember(context) {
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

@Composable internal fun TvTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), content: @Composable RowScope.() -> Unit) {
    val tv = isTv()
    val paint = if (tv) Modifier.heightIn(min = 48.dp).then(ImmediateFocusPaint(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
        buttonBackground = if (enabled) colors.containerColor else colors.disabledContainerColor)) else Modifier
    androidx.compose.material3.TextButton(onClick, modifier.then(paint), enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = if (tv) colors.copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent) else colors,
        content = content)
}

@Composable internal fun TvButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val tv = isTv()
    val colors = if (tv) ButtonDefaults.buttonColors(containerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) else ButtonDefaults.buttonColors()
    val paint = if (tv) Modifier.heightIn(min = 48.dp).then(ImmediateFocusPaint(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
        buttonBackground = MaterialTheme.colorScheme.surfaceContainerHigh)) else Modifier
    androidx.compose.material3.Button(onClick, modifier.then(paint), enabled = enabled,
        shape = RoundedCornerShape(12.dp), colors = colors, content = content)
}

@Composable internal fun TvOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    if (!isTv()) {
        androidx.compose.material3.OutlinedButton(onClick, modifier, enabled = enabled, content = content)
        return
    }
    TvTextButton(onClick, modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)), enabled, content = content)
}

@Composable internal fun TvIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    androidx.compose.material3.IconButton(onClick, modifier.then(if (isTv()) Modifier.tvFocusOutline() else Modifier),
        enabled = enabled, content = content)
}
