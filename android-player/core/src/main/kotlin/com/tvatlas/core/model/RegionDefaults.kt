package com.tvatlas.core.model

private val hongKongLabel = Regex("(?i)(?<![a-z])(?:hk|hkg|hong[ _-]*kong)(?![a-z])")

fun isHongKongLabel(value: String): Boolean =
    value.contains("香港") || value.contains("🇭🇰") || hongKongLabel.containsMatchIn(value)

private val cctvPrefix = Regex("(?i)^cctv[ -]?\\d")
private val channelNameSeparators = Regex("[\\s_·-]")
private val channelQualitySuffix = Regex("(?:高清|超高清|hd|fhd|uhd)$")
private val cctvNumber = Regex("^cctv(\\d{1,2})(?:[+频道套].*)?$")
private val hongKongChannelNames = setOf("翡翠台", "明珠台", "TVB Plus", "翡翠台PLUS", "ViuTV", "ViuTVsix", "HOY TV", "HOY Infotainment")
fun Channel.isHongKong(): Boolean = isHongKongLabel(group) || tvgId?.substringBefore('@')?.endsWith(".hk", true) == true ||
    (playbackRegion() == null && name in hongKongChannelNames)

/** Stable sort keeps the playlist order within each region. */
fun List<Channel>.hongKongFirst(): List<Channel> {
    val (hongKong, remaining) = partition { it.isHongKong() }
    return hongKong + remaining
}

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
    PlaybackRegion.HONG_KONG -> "澳门频道"
    PlaybackRegion.SINGAPORE, PlaybackRegion.UNITED_STATES, PlaybackRegion.UNITED_KINGDOM, PlaybackRegion.THAILAND -> "${playbackRegion()!!.label}频道"
    null -> "其他频道"
}

/** Presentation only: saved groups, channel identities and routing rules stay unchanged. */
fun Channel.browseRegion(): String = when {
    isHongKong() -> "香港"
    group.contains("澳门") || group.contains("澳門") || tvgId?.substringBefore('@')?.endsWith(".mo", true) == true -> "澳门"
    playbackRegion() != null -> requireNotNull(playbackRegion()).label
    cctvPrefix.containsMatchIn(name) || name.contains("央视") -> "大陆"
    else -> group.substringBefore('·').substringBefore('|').trim().takeIf {
        it in setOf("美国", "英国", "加拿大", "澳大利亚", "法国", "德国", "新加坡", "马来西亚", "泰国", "印度", "卡塔尔")
    } ?: "其他"
}

fun Channel.browseType(): String {
    val label = "$name $group".lowercase()
    fun has(vararg names: String) = names.any { label.contains(it.lowercase()) }
    when (browseRegion()) {
        "香港" -> when {
            has("viutv", "viu tv") || streams.any { it.url.contains("viu.tv/", true) } -> return "ViuTV"
            has("hoy", "奇妙") -> return "HOY"
            has("rthk", "港台", "香港电台", "香港電台") -> return "港台"
            has("tvb", "翡翠", "明珠", "無綫", "无线") || streams.any { it.url.contains("mytvsuper.com/", true) } -> return "TVB"
            else -> return "其他"
        }
        "大陆" -> when {
            has("cctv", "央视", "央視", "中央电视", "中央電視") -> return "CCTV"
            has("卫视", "衛視") -> return "卫视"
        }
        "台湾" -> when {
            has("tvbs") -> return "TVBS"
            has("东森", "東森") -> return "东森"
            has("中天") -> return "中天"
            has("三立") -> return "三立"
        }
        "日本" -> when {
            has("nhk") -> return "NHK"
        }
        "韩国" -> when {
            has("kbs") -> return "KBS"
            has("mbc") -> return "MBC"
            has("sbs") -> return "SBS"
            has("ebs") -> return "EBS"
        }
    }
    return when {
        has("新闻", "新聞", "news") -> "新闻"
        has("影视", "影視", "电影", "電影", "movie", "剧场", "劇場") -> "影视"
        has("体育", "體育", "sport") -> "体育"
        has("少儿", "少兒", "卡通", "动画", "動畫", "kids") -> "少儿"
        has("纪实", "紀實", "纪录", "紀錄", "documentary") -> "纪实"
        else -> "综合"
    }
}

/** Presentation only: flagship channels lead each type; equal ranks retain source order. */
fun List<Channel>.primaryChannelsFirst(): List<Channel> = map { it to it.primaryChannelRank() }
    .sortedBy { it.second }.map { it.first }

internal fun Channel.primaryChannelRank(type: String = browseType()): Int {
    val key = name.lowercase().replace(channelNameSeparators, "")
        .replace(channelQualitySuffix, "")
    val names = when (type) {
        "TVB" -> listOf(listOf("翡翠台", "翡翠", "tvbjade", "jade"), listOf("明珠台", "明珠", "tvbpearl", "pearl"), listOf("tvbplus", "翡翠台plus"))
        "ViuTV" -> listOf(listOf("viutv"), listOf("viutvsix", "viutv6"))
        "港台" -> listOf(listOf("港台电视31", "港台電視31", "rthk31"), listOf("港台电视32", "港台電視32", "rthk32"), listOf("港台电视33", "港台電視33", "rthk33"))
        "HOY" -> listOf(listOf("hoytv", "奇妙电视", "奇妙電視"), listOf("hoy资讯台", "hoy資訊台", "hoyinfotainment"))
        "卫视" -> listOf("广东卫视", "廣東衛視", "湖南卫视", "浙江卫视", "江苏卫视", "东方卫视", "北京卫视", "深圳卫视").map { listOf(it) }
        "TVBS" -> listOf(listOf("tvbs", "tvbs欢乐台", "tvbs歡樂台"), listOf("tvbs新闻台", "tvbs新聞台", "tvbs新闻", "tvbs新聞"))
        "东森" -> listOf(listOf("东森综合", "東森綜合", "东森综合台", "東森綜合台"), listOf("东森新闻", "東森新聞", "东森新闻台", "東森新聞台"))
        "中天" -> listOf(listOf("中天综合", "中天綜合", "中天综合台", "中天綜合台"), listOf("中天新闻", "中天新聞"))
        "三立" -> listOf(listOf("三立台湾台", "三立台灣台"), listOf("三立都会台", "三立都會台"))
        "NHK" -> listOf(listOf("nhk総合", "nhk综合", "nhkg", "nhkgeneral"), listOf("nhkeテレ", "nhke", "nhk教育"), listOf("nhkbs"))
        "KBS", "MBC", "SBS", "EBS" -> listOf(listOf(type.lowercase() + "1", type.lowercase()), listOf(type.lowercase() + "2"))
        else -> emptyList()
    }
    if (type == "CCTV") {
        val number = cctvNumber.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null && number in 1..17) return number
    }
    val rank = names.indexOfFirst { key in it }
    return if (rank >= 0) rank else 1000
}
