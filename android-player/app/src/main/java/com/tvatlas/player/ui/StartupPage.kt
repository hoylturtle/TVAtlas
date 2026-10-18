package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tvatlas.player.StartupState
import kotlinx.coroutines.delay

/** Loading belongs to the content pane: navigation, logs and source management stay usable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun StartupPage(state: StartupState, onRetry: () -> Unit, onSources: () -> Unit, onLogs: () -> Unit) {
    var slow by remember(state.running) { mutableStateOf(false) }
    LaunchedEffect(state.running) { if (state.running) { delay(8000); slow = true } }
    Column(Modifier.fillMaxSize().testTag("startup-page").verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("直播频道", style = MaterialTheme.typography.headlineSmall)
        Text(when {
            state.failed -> "本地频道读取未完成"
            !state.running -> "暂无可用频道"
            !state.localReady -> "正在读取本地频道"
            else -> "正在获取首次频道列表"
        }, style = MaterialTheme.typography.titleMedium)
        Text(when {
            state.failed -> "可以重试，或打开日志查看原因。已有配置会保留。"
            !state.running -> "请添加频道源，或检查网络后重试。"
            state.localReady -> "已处理 ${state.completed} / ${state.total} 个来源。频道就绪后自动显示，您也可以先使用其他页面。"
            else -> "先显示本地内容，订阅更新在后台进行。"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.running) {
            if (state.total > 0) LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            // Static placeholders avoid a full-screen animated splash on slower televisions.
            repeat(3) {
                Box(Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(8.dp)))
            }
        }
        if (slow && state.running) Text("等待时间较长；您可以打开频道源或日志，应用无需停留在此页。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.running) TvButton(onClick = onRetry) { Text("重新加载") }
            TvOutlinedButton(onClick = onSources) { Text("管理频道源") }
            TvTextButton(onClick = onLogs) { Text("查看日志") }
        }
    }
}
