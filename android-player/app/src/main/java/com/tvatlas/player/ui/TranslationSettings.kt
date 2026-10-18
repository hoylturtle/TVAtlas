package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvatlas.player.PlayerViewModel
import com.tvatlas.player.translation.SubtitleTranslation

@Composable internal fun TranslationSettings(model: PlayerViewModel, livePictureVisible: Boolean = false, returnToLive: (() -> Unit)? = null) {
    val controller = model.subtitleTranslation
    val state by controller.state.collectAsStateWithLifecycle()
    Text("字幕翻译与设备测试", style = MaterialTheme.typography.titleMedium)
    Text("仅支持独立文字字幕，不识别画面或语音。设置页只准备模型；请在直播画面点击“翻译”进行约 45 秒性能对比，测试期间保持视频可见。",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var sourceOpen by remember { mutableStateOf(false) }
        var targetOpen by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            AtlasActionButton(onClick = { sourceOpen = true }, enabled = !state.busy) { Text("原文：${SubtitleTranslation.LANGUAGES[state.source]}") }
            DropdownMenu(sourceOpen, { sourceOpen = false }) { SubtitleTranslation.LANGUAGES.filterKeys { it != state.target }.forEach { (code, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { sourceOpen = false; controller.language(code, state.target) })
            } }
        }
        Box(Modifier.weight(1f)) {
            AtlasActionButton(onClick = { targetOpen = true }, enabled = !state.busy) { Text("译文：${SubtitleTranslation.LANGUAGES[state.target]}") }
            DropdownMenu(targetOpen, { targetOpen = false }) { SubtitleTranslation.LANGUAGES.filterKeys { it != state.source }.forEach { (code, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { targetOpen = false; controller.language(state.source, code) })
            } }
        }
    }
    Text(state.message, style = MaterialTheme.typography.bodyMedium)
    if (state.busy) {
        if (state.phase == "download") {
            val known = state.totalBytes > 0
            if (known) LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            fun mib(value: Long) = "%.1f".format(value / 1048576.0)
            Text(if (known) "当前下载任务：${mib(state.downloadedBytes)} / ${mib(state.totalBytes)} MiB · ${(state.downloadedBytes * 100 / state.totalBytes).coerceIn(0, 100)}%"
                else if (state.downloadedBytes > 0) "已下载 ${mib(state.downloadedBytes)} MiB，总大小暂未提供" else "正在获取模型下载信息", style = MaterialTheme.typography.bodySmall)
            Text("已用 ${state.elapsedSeconds} 秒" + (state.etaSeconds?.let { " · 当前任务预计还需 $it 秒" } ?: " · 剩余时间暂无法估算") +
                if (state.waitingDownload) " · 等待网络或系统调度" else "", style = MaterialTheme.typography.bodySmall)
        } else LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
    if (state.report.isNotBlank()) Text(state.report, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AtlasActionButton(onClick = { if (livePictureVisible) controller.test() else controller.prepareModels() }, enabled = !state.busy) {
            Text(if (livePictureVisible) "开始性能测试" else if (state.modelsReady) "检查本组模型" else "准备本组模型")
        }
        if (state.busy) TvTextButton(onClick = controller::cancelTest) { Text("取消本次测试") }
        else TvTextButton(onClick = controller::removeModels) { Text("移除本组模型") }
    }
    AtlasActionButton(onClick = controller::prepareChineseModels, enabled = !state.busy) { Text("准备英／日／韩 → 中文模型") }
    if (!livePictureVisible && returnToLive != null) TvTextButton(onClick = returnToLive) { Text("返回直播：播放频道后点击“翻译”测试") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("开启字幕翻译（测试通过后开放）", Modifier.weight(1f))
        Switch(checked = state.enabled, enabled = livePictureVisible && !state.busy && state.eligible,
            onCheckedChange = { if (it) controller.enable() else controller.disable() })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("双语显示", Modifier.weight(1f))
        Switch(checked = state.bilingual, onCheckedChange = controller::bilingual)
    }
    Text("模型首次下载后保存在本机，后续翻译无需联网。当前引擎不支持从 APK 导入语言包；准备模型不代表性能测试通过。测试结果对本机和所选语言组合有效，7 天后需重测。",
        style = MaterialTheme.typography.bodySmall)
}
