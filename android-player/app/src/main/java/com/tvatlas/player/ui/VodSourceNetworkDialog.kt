package com.tvatlas.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvatlas.core.model.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun VodSourceNetworkDialog(model: com.tvatlas.player.PlayerViewModel, source: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sources by model.vodSubscriptions.state.collectAsStateWithLifecycle()
    val title = sources.sources.firstOrNull { it.api == source }?.name ?: "影视源"
    val library by model.library.collectAsStateWithLifecycle()
    val subscriptions by model.subscriptions.collectAsStateWithLifecycle()
    val selected by model.vodRoute.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("仅设置这个来源的访问网络；影视页统一合并展示，不自动更改网络。", style = MaterialTheme.typography.bodySmall) }
            item { TvTextButton(onClick = { model.setVodRoute(RouteTarget.DIRECT) }, modifier = Modifier.fillMaxWidth().selectionOutline(selected == RouteTarget.DIRECT)) { Text("直连") } }
            items(library.profiles.filter { it.enabled }, key = { it.id }) { profile ->
                TvTextButton(onClick = { model.setVodRoute(RouteTarget.proxy(profile.id)) }, modifier = Modifier.fillMaxWidth().selectionOutline(selected.profile == profile.id)) {
                    Text(listOfNotNull(subscriptions.firstOrNull { it.id == profile.subscriptionId }?.name, profile.name).joinToString(" · "))
                }
            }
        }
    }, confirmButton = { TvTextButton(onClick = onDismiss) { Text("完成") } })
}
