package com.tvatlas.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tvatlas.player.transfer.LogShareSession
import com.tvatlas.player.transfer.lanAddress
import kotlinx.coroutines.*

@Composable internal fun LogShareDialog(snapshot: () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<LogShareSession?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(300) }
    LaunchedEffect(generation) {
        var created: LogShareSession? = null
        qr = null; error = null
        try {
            val current = withContext(Dispatchers.IO) {
                LogShareSession(lanAddress(context) ?: throw IllegalStateException("No LAN"), snapshot()).also { created = it }
            }
            session = current
            qr = withContext(Dispatchers.Default) { phoneQrBitmap(current.pageUrl) }
            while (current.active.value) { seconds = current.remainingSeconds; delay(500) }
            error = "二维码已失效，请重新生成"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法创建日志页面，请确认电视已连接局域网" }
        finally { created?.close() }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session?.close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session?.close() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("手机扫码获取日志") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手机与电视连接同一局域网，扫码后可查看、复制或下载 TXT。")
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            else qr?.let {
                Image(it.asImageBitmap(), "日志页面二维码", Modifier.size(200.dp).background(Color.White))
                Text("剩余 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                Text(session?.pageUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
            } ?: CircularProgressIndicator()
            Text("分享的是当前日志快照；关闭窗口后链接失效，不上传到云端。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TvTextButton(onClick = { session?.close(); session = null; generation++ }) { Text("重新生成") } },
        dismissButton = { TvTextButton(onClick = onDismiss) { Text("关闭") } })
}
