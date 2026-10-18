package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.tvatlas.core.model.httpUri
import com.tvatlas.player.transfer.TransferKind

@Composable internal fun MediaSubscriptionDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(false) }
    if (phone) {
        PhoneTransferDialog(TransferKind.MEDIA, { phone = false }) { input -> name = input.name; url = input.url; phone = false }
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加统一订阅") }, text = {
        Column {
            Text("一份 JSON 可包含影视 sources 和直播 liveSources，也支持现有原生 TVBox 接口配置。")
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(url, { url = it.take(8192); error = false }, label = { Text("HTTP(S) 订阅地址") }, singleLine = true)
            if (error) Text("请输入有效的 HTTP(S) 地址", color = MaterialTheme.colorScheme.error)
            TvTextButton(onClick = { phone = true }) { Text("手机扫码输入") }
        }
    }, confirmButton = { TvButton(onClick = {
        if (httpUri(url.trim()) == null) error = true else onSave(name, url.trim())
    }) { Text("导入并同步") } }, dismissButton = { TvTextButton(onClick = onDismiss) { Text("取消") } })
}
