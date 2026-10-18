package com.tvatlas.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.ensureActive
import coil.decode.SvgDecoder
import com.tvatlas.player.R
import com.tvatlas.core.model.Channel

@Composable internal fun AppNavigationIcon(index: Int) {
    Icon(when (index) {
        0 -> Icons.Default.PlayArrow
        1 -> Icons.AutoMirrored.Filled.List
        4 -> AtlasSymbols.Screen
        2 -> AtlasSymbols.Route
        else -> Icons.Default.Settings
    }, contentDescription = null, modifier = Modifier.size(24.dp))
}

/** Reserve a consistent logo tile even when a source has no usable artwork. */
@Composable internal fun ChannelArtwork(channel: Channel, playing: Boolean = false,
    allowNetwork: () -> Boolean = { false }, modifier: Modifier = Modifier.width(64.dp).height(48.dp), compactLogo: Boolean = false) {
    val bundled = bundledChannelArtwork(channel.name)
    val symbolOnly = compactLogo && bundled in listOf(R.drawable.channel_jade, R.drawable.channel_pearl)
    val tileColor = Color.Transparent
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(tileColor)
        .semantics { contentDescription = "${channel.name}频道标识" }, contentAlignment = Alignment.Center) {
        val fallback: @Composable () -> Unit = {
            val text = when (channel.name) {
                "翡翠台" -> "翡翠"
                "明珠台" -> "明珠"
                else -> channel.name.take(8)
            }
            Text(text, Modifier.padding(4.dp).clearAndSetSemantics {}, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2)
        }
        if (bundled != null) Image(painterResource(bundled), contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(5.dp).clipToBounds(),
            contentScale = if (symbolOnly) ContentScale.Crop else ContentScale.Fit,
            alignment = if (symbolOnly) Alignment.CenterStart else Alignment.Center)
        else if (channel.logo.isNullOrBlank()) fallback() else SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(channel.logo)
                .decoderFactory(SvgDecoder.Factory())
                .diskCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(if (allowNetwork()) CachePolicy.ENABLED else CachePolicy.DISABLED)
                .size(156).build(),
            contentDescription = null, modifier = Modifier.fillMaxSize().padding(5.dp),
            contentScale = ContentScale.Fit, loading = { fallback() }, error = { fallback() })
        if (playing) Box(Modifier.align(Alignment.BottomEnd).size(16.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 6.dp)), contentAlignment = Alignment.Center) {
            Text("▶", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.semantics { contentDescription = "正在播放" })
        }
    }
}


private fun bundledChannelArtwork(name: String): Int? = when (name.lowercase().replace(" ", "")) {
    "tvbplus" -> extraBundledArtwork("TVB Plus")
    "tvb新闻台", "tvb新聞台", "tvbnews" -> extraBundledArtwork("无线新闻台")
    "翡翠台", "翡翠", "tvbjade" -> R.drawable.channel_jade
    "明珠台", "明珠", "tvbpearl" -> R.drawable.channel_pearl
    "viutv" -> R.drawable.channel_viu
    "viutvsix", "viutv6" -> R.drawable.channel_viusix
    "港台电视31", "港台電視31", "rthk31" -> R.drawable.channel_rthk31
    "港台电视32", "港台電視32", "rthk32" -> R.drawable.channel_rthk32
    "港台电视33", "港台電視33", "rthk33" -> R.drawable.channel_rthk33
    else -> extraBundledArtwork(name)
}

/** Prefetch sequentially, suspend/cancel with the screen, and obey current network consent. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
@androidx.compose.runtime.Composable internal fun CacheChannelArtwork(channels: List<Channel>, allowNetwork: () -> Boolean) {
    val context = LocalContext.current
    val connectivity = remember(context) { context.getSystemService(android.net.ConnectivityManager::class.java) }
    var networkRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(connectivity) {
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { networkRevision++ }
            override fun onLost(network: android.net.Network) { networkRevision++ }
        }
        val registered = runCatching { connectivity.registerNetworkCallback(android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback) }.isSuccess
        onDispose { if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    val allowed = allowNetwork()
    androidx.compose.runtime.LaunchedEffect(channels, allowed, networkRevision) {
        if (!allowed) return@LaunchedEffect
        kotlinx.coroutines.delay(1500) // Let the first interactive frame and cached channel list settle.
        val loader = coil.Coil.imageLoader(context)
        channels.asSequence().filter { bundledChannelArtwork(it.name) == null }
            .mapNotNull { it.logo?.takeIf { url -> url.startsWith("https://") || url.startsWith("http://") } }
            .distinct().take(512).forEach { url ->
                kotlin.coroutines.coroutineContext.ensureActive()
                if (!allowNetwork()) return@LaunchedEffect
                val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    loader.diskCache?.openSnapshot(url)?.use { true } ?: false
                }
                if (!cached) {
                    loader.execute(ImageRequest.Builder(context).data(url).size(156)
                        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.ENABLED).build())
                    kotlinx.coroutines.delay(80)
                }
            }
    }
}
