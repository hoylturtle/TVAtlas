package com.tvatlas.player.playback

/** Repeated remote/touch confirmation must not cancel a healthy or still connecting request. */
internal object LivePlayRequest {
    fun shouldReuse(channelId: String, streamId: String?, status: PlaybackStatus,
        hasPlayer: Boolean, forceRestart: Boolean): Boolean =
        !forceRestart && channelId == status.channelId && streamId == status.selectedStreamId &&
            status.error == null && (status.loading || hasPlayer)
}
