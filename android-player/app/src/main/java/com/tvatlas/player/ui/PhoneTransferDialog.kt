package com.tvatlas.player.ui

import android.graphics.Bitmap
import android.content.Context
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tvatlas.player.transfer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun createLanTransferSession(context: Context, kind: TransferKind): PhoneTransferSession =
    PhoneTransferSession(lanAddress(context) ?: throw IllegalStateException("未连接局域网"), kind)

internal fun phoneQrBitmap(url: String): Bitmap {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
}

@Composable internal fun PhoneTransferDialog(kind: TransferKind, onDismiss: () -> Unit,
    sessionFactory: (Context, TransferKind) -> PhoneTransferSession = ::createLanTransferSession,
    onReceive: (TransferInput) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<PhoneTransferSession?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(300) }
    val receive by rememberUpdatedState(onReceive)
    LaunchedEffect(generation) {
        error = null; qr = null
        var receiver: PhoneTransferSession? = null
        try {
            val created = withContext(Dispatchers.IO) {
                sessionFactory(context, kind).also { receiver = it }
            }
            session = created
            qr = withContext(Dispatchers.Default) { phoneQrBitmap(created.pageUrl) }
            created.state.collect { state ->
                state.received?.let { receive(it) }
                if (!state.active && state.received == null) error = state.error ?: "临时页面已关闭，请重新生成"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { session?.close(); error = "无法建立临时页面，请将电视连接 Wi-Fi 或网线后重试" }
        finally { receiver?.close() }
    }
    LaunchedEffect(session) {
        while (session?.state?.value?.active == true) { seconds = session?.remainingSeconds ?: 0; delay(1000) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session?.close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session?.close() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("手机扫码输入${when (kind) { TransferKind.MEDIA -> "统一订阅"; TransferKind.PLAYLIST -> "频道订阅"; TransferKind.PROXY -> "代理订阅"; TransferKind.CLOUD -> "网盘分享"; TransferKind.VIDEO -> "视频直链" }}") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("手机与电视连接同一局域网，扫码后粘贴地址，发送到电视确认。")
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            else qr?.let {
                Image(it.asImageBitmap(), contentDescription = "手机输入订阅地址的临时页面二维码",
                    modifier = Modifier.size(200.dp).background(Color.White))
                Text("剩余 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                Text(session?.pageUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text("接收一次或关闭窗口后失效。", style = MaterialTheme.typography.bodySmall)
            } ?: CircularProgressIndicator()
        }
    }, confirmButton = { if (error != null) TvTextButton(onClick = { session?.close(); session = null; generation++ }) { Text("重新生成") } },
        dismissButton = { TvTextButton(onClick = onDismiss) { Text("返回") } })
}
