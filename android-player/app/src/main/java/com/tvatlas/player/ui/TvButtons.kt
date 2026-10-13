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
import androidx.compose.ui.focus.onFocusChanged
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
    colors: ButtonColors = ButtonDefaults.textButtonColors(), content: @Composable RowScope.() -> Unit) {
    val tv = isTv()
    var focused by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick, modifier.then(if (tv) Modifier.heightIn(min = 52.dp)
        .onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp)) else Modifier),
        enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = if (tv && focused) ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary) else colors, content = content)
}

@Composable internal fun TvButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val tv = isTv()
    var focused by remember { mutableStateOf(false) }
    androidx.compose.material3.Button(onClick, modifier.then(if (tv) Modifier.heightIn(min = 52.dp)
        .onFocusChanged { focused = it.hasFocus }.border(if (focused) 3.dp else 0.dp,
            if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp)) else Modifier),
        enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = if (tv && focused) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            else ButtonDefaults.buttonColors(), content = content)
}

@Composable internal fun TvOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    if (!isTv()) {
        androidx.compose.material3.OutlinedButton(onClick, modifier, enabled = enabled, content = content)
        return
    }
    TvTextButton(onClick, modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)), enabled, content = content)
}
