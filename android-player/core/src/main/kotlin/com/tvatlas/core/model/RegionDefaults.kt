package com.tvatlas.core.model

private val hongKongLabel = Regex("(?i)(?<![a-z])(?:hk|hkg|hong[ _-]*kong)(?![a-z])")

fun isHongKongLabel(value: String): Boolean =
    value.contains("香港") || value.contains("🇭🇰") || hongKongLabel.containsMatchIn(value)

fun Channel.isHongKong(): Boolean = isHongKongLabel(group) || tvgId?.endsWith(".hk", true) == true

/** Stable sort keeps the playlist order within each region. */
fun List<Channel>.hongKongFirst(): List<Channel> = sortedBy { if (it.isHongKong()) 0 else 1 }

/** Normalize legacy bare region groups without rewriting saved channel IDs or manual routes. */
fun Channel.normalizeHongKongGroup(): Channel {
    if (!isHongKong()) return this
    val raw = group.trim()
    val genre = when {
        raw.contains("新闻") || raw.contains("新聞") -> "新闻"
        raw.contains("影视") || raw.contains("影視") || raw.contains("电影") || raw.contains("電影") || raw.contains("剧集") || raw.contains("劇集") -> "影视"
        raw.contains("体育") || raw.contains("體育") -> "体育"
        raw.contains("音乐") || raw.contains("音樂") -> "音乐"
        raw.contains("纪实") || raw.contains("紀實") -> "纪实"
        raw.contains("综艺") || raw.contains("綜藝") -> "综艺"
        raw.contains("公共电视") -> "公共电视"
        name.contains("新闻") || name.contains("新聞") || name.contains("資訊台") || name.contains("资讯台") -> "新闻"
        else -> "综合"
    }
    return copy(group = "香港 · $genre")
}
