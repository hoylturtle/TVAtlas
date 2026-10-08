package com.tvatlas.core.model

import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest

@Serializable enum class RouteType { DIRECT, PROXY, AUTO }
@Serializable data class RouteTarget(
    val type: RouteType,
    val profile: String? = null,
    val `try`: List<String> = emptyList(),
) {
    companion object {
        val DIRECT = RouteTarget(RouteType.DIRECT)
        val AUTO = RouteTarget(RouteType.AUTO)
        fun proxy(id: String) = RouteTarget(RouteType.PROXY, id)
    }
}
@Serializable enum class ProxyType { HTTP, SOCKS5 }
@Serializable data class ProxyProfile(
    val id: String, val name: String, val type: ProxyType, val host: String,
    val port: Int, val enabled: Boolean = true,
)
data class Playlist(
    val id: String, val name: String, val url: String, val enabled: Boolean = true,
    val refreshIntervalHours: Int = 24, val lastUpdatedAt: Long? = null,
)
data class Stream(
    val id: String, val url: String, val sourcePlaylistId: String,
    val manualRoute: RouteTarget? = null, val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null, val failureCount: Int = 0,
)
data class Channel(
    val id: String, val name: String, val group: String, val logo: String? = null,
    val tvgId: String? = null, val streams: List<Stream>, val manualRoute: RouteTarget? = null,
)
data class StreamContext(val channel: Channel, val stream: Stream)
data class RouteAttempt(val streamId: String, val streamUrl: String, val target: RouteTarget, val matchedRule: String)
data class RoutePlan(val attempts: List<RouteAttempt>)
data class SuccessfulRoute(val streamId: String, val target: RouteTarget, val at: Long)
fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun httpUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
    it.scheme?.lowercase() in listOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
}
