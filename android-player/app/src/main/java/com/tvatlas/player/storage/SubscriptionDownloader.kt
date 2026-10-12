package com.tvatlas.player.storage

import com.tvatlas.player.network.readCancellable

import com.tvatlas.core.model.*
import com.tvatlas.core.subscription.ClashSubscription
import com.tvatlas.core.subscription.SubscriptionException
import com.tvatlas.player.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal fun subscriptionTargets(selected: RouteTarget, profiles: List<ProxyProfile>): List<RouteTarget> =
    if (selected.type != RouteType.AUTO) listOf(selected) else
        listOf(RouteTarget.DIRECT) + profiles.filter { it.enabled }
            .sortedBy { if (isHongKongLabel(it.name)) 0 else 1 }.take(2).map { RouteTarget.proxy(it.id) }

/** Retry transport failures only; never replace saved nodes with a failed or malformed response. */
class SubscriptionDownloader(private val client: (RouteTarget) -> OkHttpClient, private val log: DebugLog? = null) {
    suspend fun download(url: String, targets: List<RouteTarget>): String = withContext(Dispatchers.IO) {
        if (httpUri(url) == null) throw SubscriptionException("请输入合法的 HTTP(S) 订阅地址")
        var reason = "网络连接失败"
        for ((index, target) in targets.distinct().withIndex()) {
            try {
                log?.event("INFO", "SUBSCRIPTION_ROUTE", "attempt=${index + 1} route=${target.type}")
                val network = client(target).newBuilder().followRedirects(true).followSslRedirects(false)
                    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
                val downloaded = network.newCall(Request.Builder().url(url).header("User-Agent", "ClashMeta/1.19.32")
                    .header("Accept", "application/yaml, text/yaml, text/plain").build()).readCancellable { response ->
                    log?.event("INFO", "SUBSCRIPTION_HTTP", "status=${response.code} host=${response.request.url.host}")
                    if (!response.isSuccessful) {
                        val message = when (response.code) {
                            401, 403 -> "订阅服务拒绝访问（HTTP ${response.code}），请检查订阅是否到期或地址是否变更"
                            404, 410 -> "订阅地址已失效（HTTP ${response.code}），请从服务商重新复制地址"
                            429 -> "更新过于频繁（HTTP 429），请稍后重试"
                            else -> "订阅下载失败（HTTP ${response.code}）"
                        }
                        if (response.code in 500..599) { reason = message; return@readCancellable null }
                        throw SubscriptionException("$message；已保留旧节点")
                    }
                    val body = response.body ?: throw SubscriptionException("订阅内容为空；已保留旧节点")
                    if (body.contentLength() > ClashSubscription.MAX_BYTES) throw SubscriptionException("订阅文件超过 2 MB；已保留旧节点")
                    val output = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            if (output.size() + count > ClashSubscription.MAX_BYTES) throw SubscriptionException("订阅文件超过 2 MB；已保留旧节点")
                            output.write(buffer, 0, count)
                        }
                    }
                    output.toString("UTF-8")
                }
                if (downloaded != null) return@withContext downloaded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) {
                reason = when (error) {
                    is SocketTimeoutException -> "连接订阅服务超时"
                    is UnknownHostException -> "无法解析订阅域名"
                    is SSLException -> "订阅服务的 TLS 连接失败"
                    else -> "订阅网络连接失败"
                }
                log?.error("SUBSCRIPTION_NETWORK", error)
            }
        }
        throw SubscriptionException("$reason；请切换订阅下载网络后重试。已保留旧节点")
    }
}
