package com.tvatlas.core.model

enum class PlaybackRegion(val label: String) { MAINLAND("大陆"), HONG_KONG("香港"), TAIWAN("台湾"), JAPAN("日本"), KOREA("韩国"), SINGAPORE("新加坡"), UNITED_STATES("美国"), UNITED_KINGDOM("英国"), THAILAND("泰国") }
private fun countryCode(value: String, pattern: String) = Regex("(?i)(?<![a-z])(?:$pattern)(?![a-z])").containsMatchIn(value)
fun playbackRegion(value: String): PlaybackRegion? = when {
    isHongKongLabel(value) || value.contains("澳门") || value.contains("澳門") || value.contains("港澳") || value.contains("🇲🇴") || countryCode(value, "mo|mac|macao|macau") -> PlaybackRegion.HONG_KONG
    value.contains("台湾") || value.contains("台灣") || value.contains("🇹🇼") || countryCode(value, "tw|twn|taiwan") -> PlaybackRegion.TAIWAN
    value.contains("日本") || value.contains("🇯🇵") || countryCode(value, "jp|jpn|japan") -> PlaybackRegion.JAPAN
    value.contains("韩国") || value.contains("韓國") || value.contains("南韩") || value.contains("🇰🇷") || countryCode(value, "kr|kor|korea") -> PlaybackRegion.KOREA
    value.contains("新加坡") || value.contains("狮城") || value.contains("獅城") || value.contains("🇸🇬") || countryCode(value, "sg|sgp|singapore") -> PlaybackRegion.SINGAPORE
    value.contains("美国") || value.contains("美國") || value.contains("🇺🇸") || countryCode(value, "us|usa|united[ _-]*states") -> PlaybackRegion.UNITED_STATES
    value.contains("英国") || value.contains("英國") || value.contains("🇬🇧") || countryCode(value, "uk|gb|gbr|united[ _-]*kingdom") -> PlaybackRegion.UNITED_KINGDOM
    value.contains("泰国") || value.contains("泰國") || value.contains("🇹🇭") || countryCode(value, "th|tha|thailand") -> PlaybackRegion.THAILAND
    value.contains("大陆") || value.contains("大陸") || value.contains("内地") || value.contains("內地") || value.contains("🇨🇳") || countryCode(value, "cn|chn|china") -> PlaybackRegion.MAINLAND
    else -> null
}
fun Channel.playbackRegion(): PlaybackRegion? = playbackRegion(group) ?: tvgId?.let { id ->
    Regex("(?i)\\.(hk|mo|tw|jp|kr|cn|sg|us|uk|gb|th)(?:@.*)?$").find(id)?.groupValues?.get(1)?.let(::playbackRegion)
}
data class NodeHealth(val delayMs: Long?, val checkedAt: Long) {
    fun fresh(now: Long) = now - checkedAt in 0..300_000
}
fun orderedRegionNodes(profiles: List<ProxyProfile>, region: PlaybackRegion, health: Map<String, NodeHealth>, now: Long): List<ProxyProfile> =
    profiles.filter { it.enabled && (if (region == PlaybackRegion.HONG_KONG) isHongKongLabel(it.name) else playbackRegion(it.name) == region) }.sortedWith(compareBy<ProxyProfile>(
        { val sample = health[it.id]?.takeIf { it.fresh(now) }; if (sample == null) 1 else if (sample.delayMs != null) 0 else 2 },
        { health[it.id]?.takeIf { it.fresh(now) }?.delayMs ?: Long.MAX_VALUE },
    ))
