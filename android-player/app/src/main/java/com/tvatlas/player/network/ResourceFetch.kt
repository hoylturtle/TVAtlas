package com.tvatlas.player.network

import com.tvatlas.player.debug.DebugLog
import com.tvatlas.player.update.UpdateException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** A structural redirect error cannot be repaired by cycling through proxy nodes. */
internal class ResourceRedirectException(message: String) : IOException(message)

internal fun resourceRedirect(from: HttpUrl, location: String?, visited: Set<HttpUrl>, hop: Int): HttpUrl {
    val next = location?.let(from::resolve) ?: throw ResourceRedirectException("来源跳转地址无效")
    if (next.username.isNotEmpty() || next.password.isNotEmpty()) throw ResourceRedirectException("来源跳转包含凭证，已停止")
    if (from.isHttps && !next.isHttps) throw ResourceRedirectException("来源要求降低连接安全性，已停止")
    if (hop >= 5 || next in visited) throw ResourceRedirectException("来源跳转次数过多或存在循环")
    return next
}

/** Follow bounded redirects explicitly: permit HTTP→HTTPS, block HTTPS→HTTP, never log Location. */
internal suspend fun fetchResourceBytes(base: OkHttpClient, request: Request, maximum: Int, log: DebugLog? = null, tag: String = "RESOURCE"): ByteArray =
    try { withTimeout(30_000) { fetchInRoute(base, request, maximum, log, tag) } }
    catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw java.net.SocketTimeoutException("资源读取超时")
    }

private suspend fun fetchInRoute(base: OkHttpClient, request: Request, maximum: Int, log: DebugLog?, tag: String): ByteArray {
    val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    var current = request
    val visited = mutableSetOf(current.url)
    repeat(6) { hop ->
        var next: HttpUrl? = null
        val bytes = client.newCall(current).readCancellable { response ->
            log?.event("INFO", "${tag}_HTTP", "host=${response.request.url.host} status=${response.code}")
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                next = resourceRedirect(current.url, response.header("Location"), visited, hop)
                log?.event("INFO", "${tag}_REDIRECT", "hop=${hop + 1} status=${response.code} from=${current.url.scheme}://${current.url.host} to=${next!!.scheme}://${next!!.host}")
                null
            } else {
                if (!response.isSuccessful) throw UpdateException("资源请求失败", response.code, response.protocol)
                val body = response.body ?: throw IOException("资源内容为空")
                if (body.contentLength() > maximum) throw IOException("资源文件过大")
                val input = body.byteStream().buffered()
                input.mark(2); val first = input.read(); val second = input.read(); input.reset()
                val decoded = if (first == 0x1f && second == 0x8b) java.util.zip.GZIPInputStream(input) else input
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                decoded.use { while (true) {
                    val count = it.read(buffer); if (count < 0) break
                    if (out.size() + count > maximum) throw IOException("资源文件过大")
                    out.write(buffer, 0, count)
                } }
                out.toByteArray()
            }
        }
        if (bytes != null) return bytes
        val target = requireNotNull(next)
        val builder = current.newBuilder().url(target)
        if (target.host != current.url.host || target.port != current.url.port || target.scheme != current.url.scheme) {
            builder.removeHeader("Cookie").removeHeader("Authorization").removeHeader("Proxy-Authorization").removeHeader("Referer")
        }
        current = builder.build()
        visited += target
    }
    throw ResourceRedirectException("来源跳转次数过多")
}
