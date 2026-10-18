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
import com.tvatlas.player.transfer.*
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun ApkUploadDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var session by remember { mutableStateOf<ApkUploadSession?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(600) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(generation) {
        var created: ApkUploadSession? = null
        qr = null
        message = null
        try {
            val current = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "apk-transfer").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }
                    ?.forEach { it.deleteRecursively() }
                ApkUploadSession(lanAddress(context) ?: error("No LAN"), directory,
                    { inspectUploadedApk(context, it) }).also { created = it }
            }
            session = current
            qr = withContext(Dispatchers.Default) { phoneQrBitmap(current.pageUrl) }
            while (current.active.value) { seconds = current.remainingSeconds; delay(500) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "无法开启接收，请确认已连接 Wi-Fi 或有线局域网" }
        finally { created?.close() }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session?.close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session?.close() }
    }
    val current = session
    val state = current?.state?.collectAsState()?.value ?: ApkUploadState()
    val active = current?.active?.collectAsState()?.value ?: true
    AlertDialog(onDismissRequest = onDismiss, title = { Text("手机扫码传安装包") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("手机与电视连接同一局域网。扫码选择单个 APK，最大 300 MB；收到后在电视确认安装。")
            val ready = state.ready
            if (ready != null) {
                Text("已收到：${ready.label}")
                Text("${ready.packageName}\n版本 ${ready.version}")
                TvButton(onClick = {
                    message = runCatching { openUploadedApk(context, ready) }
                        .getOrElse { "无法打开系统安装器，请检查电视是否允许安装应用" }
                }) { Text("打开系统安装器") }
            } else if (state.receiving || state.validating) {
                val progress = if (state.total > 0) state.received.toFloat() / state.total else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(if (state.validating) "上传完成，正在检查安装包…" else "已上传 ${(progress * 100).toInt()}%（${state.received / 1024 / 1024} / ${state.total / 1024 / 1024} MB）")
            } else if (!active) Text("接收已关闭，请重新生成二维码")
            else qr?.let {
                Image(it.asImageBitmap(), "安装包上传二维码", Modifier.size(190.dp).background(Color.White))
                Text("剩余 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                Text(current?.pageUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
            } ?: CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it) }
            Text("关闭窗口后上传链接失效。仅接收文件，不会自动安装。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TvTextButton(onClick = { session?.close(); session = null; generation++ }) { Text("重新生成") } },
        dismissButton = { TvTextButton(onClick = onDismiss) { Text("关闭") } })
}
