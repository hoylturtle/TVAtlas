package com.tvatlas.player.update

import com.tvatlas.core.model.*
import com.tvatlas.player.debug.DebugLog
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

internal fun updateTargets(selected: RouteTarget, profiles: List<ProxyProfile>): List<RouteTarget> {
    if (selected.type == RouteType.DIRECT) return listOf(RouteTarget.DIRECT)
    val backups = profiles.filter { it.enabled && it.id != selected.profile }
        .sortedBy { if (isHongKongLabel(it.name)) 0 else 1 }.take(2).map { RouteTarget.proxy(it.id) }
    return ((if (selected.type == RouteType.PROXY) listOf(selected) else listOf(RouteTarget.DIRECT)) + backups).distinct()
}

/** GET-only update traffic: bounded node failover and one HTTP/1.1 retry for an HTTP/2 rejection. */
internal suspend fun <T> withUpdateRoutes(targets: List<RouteTarget>, download: Boolean,
    client: suspend (RouteTarget) -> OkHttpClient, label: (RouteTarget) -> String = { it.type.name },
    log: DebugLog? = null, routeTag: String = "UPDATE_ROUTE", compatTag: String = "UPDATE_COMPAT", work: suspend (OkHttpClient) -> T): T {
    var last: Exception? = null
    for ((index, target) in targets.distinct().withIndex()) {
        currentCoroutineContext().ensureActive()
        try {
            log?.event("INFO", routeTag, "attempt=${index + 1} route=${target.type} proxy=${label(target)} download=$download")
            val network = withContext(Dispatchers.IO) { client(target).newBuilder()
                .followRedirects(true).followSslRedirects(false)
                .connectTimeout(if (download) 10 else 5, TimeUnit.SECONDS)
                .readTimeout(if (download) 30 else 8, TimeUnit.SECONDS)
                .callTimeout(if (download) 300 else 8, TimeUnit.SECONDS).build() }
            try { return work(network) }
            catch (error: UpdateException) {
                if (error.httpStatus != 400 || error.protocol != Protocol.HTTP_2) throw error
                log?.event("WARN", compatTag, "HTTP 400 over h2; retry same route with http/1.1")
                val freshPool = ConnectionPool()
                try { return work(network.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).connectionPool(freshPool).build()) }
                finally { freshPool.evictAll() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { log?.error(routeTag, error); last = error }
    }
    throw last ?: UpdateException("没有可用的更新网络")
}
