package com.tvatlas.player.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.core.model.RouteType
import com.tvatlas.player.PlayerViewModel

@Composable internal fun PlaybackNetworkInfo(model: PlayerViewModel, target: RouteTarget?) {
    val health by model.nodeHealth.collectAsStateWithLifecycle()
    val direct by model.directHealth.collectAsStateWithLifecycle()
    val speed by model.playback.speed.collectAsStateWithLifecycle()
    val sample = if (target?.type == RouteType.DIRECT) direct else health[target?.profile]
    val ms = sample?.takeIf { it.fresh(System.currentTimeMillis()) }?.delayMs?.let { "$it ms" } ?: "-- ms"
    Text("延迟 $ms · 网速 ${trafficSize(speed)}/s", Modifier.widthIn(max = 260.dp), style = MaterialTheme.typography.bodySmall,
        color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
