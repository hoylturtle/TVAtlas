package com.tvatlas.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.tvatlas.core.epg.Programme
import com.tvatlas.core.model.Channel
import com.tvatlas.core.model.httpUri
import com.tvatlas.player.epg.EpgStore
import kotlinx.coroutines.delay

/** Static artwork only: never open a player or contact a stream to obtain an image. */
@Composable internal fun ChannelBackdrop(channel: Channel, programme: Programme?, allowNetwork: () -> Boolean,
    modifier: Modifier = Modifier) {
    val accents = listOf(Color(0xFF253855), Color(0xFF28433F), Color(0xFF3E3152), Color(0xFF3D3540))
    val accent = accents[(channel.group.hashCode() and Int.MAX_VALUE) % accents.size]
    val now = System.currentTimeMillis()
    val image = programme?.takeIf { now >= it.start && now < it.end }?.imageUrl
        ?.takeIf { it.length <= 8192 && httpUri(it) != null }
    Box(modifier.background(Brush.linearGradient(listOf(accent, Color(0xFF0C111B))))) {
        if (image != null) SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(image)
                .diskCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(if (allowNetwork()) CachePolicy.ENABLED else CachePolicy.DISABLED)
                .size(960, 540).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            loading = {}, error = {})
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)))
    }
}

/** Read the existing guide cache; selecting a channel does not trigger an EPG download here. */
@Composable internal fun CachedChannelIdlePicture(store: EpgStore, allowNetwork: () -> Boolean,
    message: String, channel: Channel?, loading: Boolean) {
    val guide by store.state.collectAsStateWithLifecycle()
    var now by remember(channel?.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(channel?.id) { while (channel != null) { delay(60_000); now = System.currentTimeMillis() } }
    val programme = channel?.let { selected ->
        remember(guide, selected.id, selected.name, selected.tvgId, now) {
            guide.entries(selected).firstOrNull { now >= it.start && now < it.end }
        }
    }
    AtlasIdlePicture(message, channel, loading, programme, allowNetwork)
}
