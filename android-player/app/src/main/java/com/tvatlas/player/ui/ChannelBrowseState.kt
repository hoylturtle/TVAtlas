package com.tvatlas.player.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.tvatlas.core.model.Channel
import com.tvatlas.core.model.ChannelBrowseIndex
import com.tvatlas.player.AtlasApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class BrowseSnapshot(val input: List<Channel>, val index: ChannelBrowseIndex)

/** Recomposition, playback telemetry and focus changes do not rebuild the channel index. */
@Composable internal fun rememberChannelBrowseIndex(channels: List<Channel>): ChannelBrowseIndex? {
    val app = LocalContext.current.applicationContext as? AtlasApplication
    val log = app?.debugLog
    val index by produceState<BrowseSnapshot?>(null, channels) {
        value = withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            val cached = app?.channelBrowseCache?.get(channels)
            val result = cached?.index ?: ChannelBrowseIndex.build(channels)
            log?.event("INFO", "CHANNEL_INDEX", "channels=${channels.size} reused=${cached?.reused ?: 0} calculated=${cached?.calculated ?: channels.size} diskRead=${cached?.diskRead ?: false} saved=${cached?.saved ?: false} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            BrowseSnapshot(channels, result)
        }
    }
    return index?.takeIf { it.input == channels }?.index
}
