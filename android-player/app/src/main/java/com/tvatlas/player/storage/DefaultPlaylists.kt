package com.tvatlas.player.storage

import com.tvatlas.core.model.Playlist
import com.tvatlas.core.model.stableId
import com.tvatlas.player.source.MyTvSuperSource

object DefaultPlaylists {
    const val CHANNELS_URL = "https://raw.githubusercontent.com/hoylturtle/TVAtlas/main/tvatlas.m3u"
    val entries = listOf(
        Playlist(stableId(MyTvSuperSource.PAGE), "翡翠台官方（实验）", MyTvSuperSource.PAGE),
        Playlist(stableId(CHANNELS_URL), "TVAtlas 频道订阅", CHANNELS_URL),
    )
}
