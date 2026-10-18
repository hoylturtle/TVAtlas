package com.tvatlas.core.vod

import com.tvatlas.core.model.httpUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable data class SubscribedLiveSource(val name: String, val url: String, val epg: String? = null)
data class MediaSubscriptionParse(val vod: List<SubscribedVodSource>, val live: List<SubscribedLiveSource>,
    val native: VodSubscriptionParse? = null)

/** Data-only sources/liveSources JSON and existing native TVBox adapters; never executes scripts or spiders. */
class MediaSubscriptionLimitException : IllegalArgumentException("直播列表超出导入上限（每列表 2000 个频道、统一订阅共 6000 条线路），已保留原配置")

object MediaSubscription {
    fun checkedLiveBudget(channels: Int, streams: Int, existing: Int): Int {
        val total = existing.toLong() + streams
        if (channels !in 1..2000 || streams < 1 || total > 6000 || total < 0) throw MediaSubscriptionLimitException()
        return total.toInt()
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun parse(text: String, origin: String): MediaSubscriptionParse {
        require(text.toByteArray().size <= 2 * 1024 * 1024) { "订阅超过 2 MiB" }
        val root = json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject ?: error("订阅需要 JSON 对象")
        if (root["sites"] is JsonArray) return VodSubscription.parse(text, origin).let { MediaSubscriptionParse(it.sources, emptyList(), it) }
        require(root["sources"] is JsonArray || root["liveSources"] is JsonArray) { "订阅需要 sources 或 liveSources" }
        fun rows(field: String): List<JsonObject> {
            val array = root[field] as? JsonArray ?: return emptyList()
            require(array.size <= 500) { "订阅来源过多" }
            return array.mapNotNull { it as? JsonObject }
        }
        fun JsonObject.value(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val vod = rows("sources").mapNotNull { row ->
            val url = address(row.value("url"), origin) ?: return@mapNotNull null
            SubscribedVodSource(row.value("name").ifBlank { URI(url).host }.take(80), url)
        }.distinctBy { it.api }.take(40)
        val live = rows("liveSources").mapNotNull { row ->
            val url = address(row.value("url"), origin) ?: return@mapNotNull null
            SubscribedLiveSource(row.value("name").ifBlank { URI(url).host }.take(80), url,
                address(row.value("epg"), origin))
        }.distinctBy { it.url }.take(30)
        require(vod.isNotEmpty() || live.isNotEmpty()) { "订阅没有有效的 HTTP(S) 来源，保留旧配置" }
        return MediaSubscriptionParse(vod, live)
    }
    private fun address(raw: String, origin: String): String? {
        if (raw.isBlank() || raw.length > 8192 || raw.any { it.isISOControl() }) return null
        val url = runCatching { URI(origin).resolve(raw).normalize().toASCIIString() }.getOrNull() ?: return null
        val uri = httpUri(url) ?: return null
        val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".local") || host.contains(':') || host == "0.0.0.0" ||
            host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.") ||
            Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(host)) return null
        if (Regex("\\.(jar|js|py)(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)) return null
        return url
    }
}
