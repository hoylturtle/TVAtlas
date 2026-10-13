package com.tvatlas.core.model

private val hongKongLabel = Regex("(?i)(?<![a-z])(?:hk|hkg|hong[ _-]*kong)(?![a-z])")

fun isHongKongLabel(value: String): Boolean =
    value.contains("香港") || value.contains("🇭🇰") || hongKongLabel.containsMatchIn(value)

private val hongKongChannelNames = setOf("翡翠台", "明珠台", "TVB Plus", "翡翠台PLUS", "ViuTV", "ViuTVsix", "HOY TV", "HOY Infotainment")
fun Channel.isHongKong(): Boolean = isHongKongLabel(group) || tvgId?.substringBefore('@')?.endsWith(".hk", true) == true ||
    (playbackRegion() == null && name in hongKongChannelNames)

/** Stable sort keeps the playlist order within each region. */
fun List<Channel>.hongKongFirst(): List<Channel> = sortedBy { if (it.isHongKong()) 0 else 1 }

/** Normalize legacy bare region groups without rewriting saved channel IDs or manual routes. */
fun Channel.normalizeHongKongGroup(): Channel {
    if (!isHongKong()) return this
    val raw = group.trim()
    val genre = when {
        raw.contains("新闻") || raw.contains("新聞") -> "新闻"
        raw.contains("影视") || raw.contains("影視") || raw.contains("电影") || raw.contains("電影") || raw.contains("剧集") || raw.contains("劇集") -> "影视"
        name.contains("新闻") || name.contains("新聞") || name.contains("資訊台") || name.contains("资讯台") -> "新闻"
        else -> "综合"
    }
    return copy(group = "香港 · $genre")
}

/** Browsing categories only; do not rewrite non-HK groups used by imported rules. */
fun Channel.browseGroup(): String = if (isHongKong()) normalizeHongKongGroup().group else when (playbackRegion()) {
    PlaybackRegion.MAINLAND -> "大陆频道"
    PlaybackRegion.TAIWAN -> "台湾频道"
    PlaybackRegion.JAPAN -> "日本频道"
    PlaybackRegion.KOREA -> "韩国频道"
    PlaybackRegion.HONG_KONG -> "香港 · 综合"
    null -> "其他频道"
}
