package com.tvatlas.core.vod

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable data class SubscribedVodSource(val name: String, val api: String)
data class VodSubscriptionParse(val sources: List<SubscribedVodSource>, val unsupported: Int, val unsupportedNames: Set<String> = emptySet())
/** Data-only TVBox native JSON adapters. Never loads spider jars, JS, JNI or parser services. */
object VodSubscription {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun parse(text: String, origin: String): VodSubscriptionParse {
        require(text.toByteArray().size <= 2 * 1024 * 1024) { "订阅超过 2 MiB" }
        val root = json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        val sites = root["sites"] as? JsonArray ?: throw IllegalArgumentException("不是可识别的影视订阅配置")
        require(sites.size <= 500) { "来源过多，已保留旧配置" }
        var unsupported = 0
        val unsupportedNames = mutableSetOf<String>()
        val sources = sites.mapNotNull { node ->
            val row = node as? JsonObject ?: return@mapNotNull null
            val type = (row["type"] as? JsonPrimitive)?.intOrNull
            val api = (row["api"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = (row["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(80)
            if (type != 1 || api.startsWith("csp_", true) || api.endsWith(".js", true)) { unsupported++; unsupportedNames += name; return@mapNotNull null }
            val resolved = runCatching { URI(origin).resolve(api).toASCIIString() }.getOrNull()
            val uri = resolved?.let { runCatching { URI(it) }.getOrNull() }
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
                api.isBlank() || uri.host.lowercase().let { host -> host == "localhost" || host.endsWith(".local") ||
                    host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.") ||
                    Regex("""172\.(1[6-9]|2[0-9]|3[01])\..*""").matches(host) || host in setOf("[::1]", "::1", "0.0.0.0") } || uri.path.orEmpty().endsWith(".jar", true) || name.isBlank()) { unsupported++; unsupportedNames += name; return@mapNotNull null }
            SubscribedVodSource(name, resolved)
        }.distinctBy { it.api }.take(40)
        return VodSubscriptionParse(sources, unsupported, unsupportedNames)
    }
}
