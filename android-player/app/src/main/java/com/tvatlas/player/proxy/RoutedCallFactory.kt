package com.tvatlas.player.proxy

import com.tvatlas.core.model.*
import com.tvatlas.core.routing.DefaultRouteResolver
import okhttp3.*
import okio.Timeout
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okio.ForwardingSource
import okio.buffer

class RequestRouteFailures {
    private val failed = mutableMapOf<String, MutableSet<RouteTarget>>()
    @Synchronized fun record(url: HttpUrl, target: RouteTarget) { failed.getOrPut("${url.scheme}://${url.host}:${url.port}") { mutableSetOf() }.add(target) }
    @Synchronized fun contains(url: HttpUrl, target: RouteTarget) = target in failed["${url.scheme}://${url.host}:${url.port}"].orEmpty()
}

/** Re-evaluates every media request and each redirect before selecting a client. */
class RoutedCallFactory(
    private val pool: ProxyClientPool, private val resolver: DefaultRouteResolver,
    private val context: StreamContext, private val sessionTarget: RouteTarget,
    private val failures: RequestRouteFailures = RequestRouteFailures(),
    private val onFailure: (RouteTarget) -> Unit = {},
) : Call.Factory {
    override fun newCall(request: Request): Call = RoutedCall(request)
    private inner class RoutedCall(private val original: Request) : Call {
        private val executed = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val active = AtomicReference<Call?>(null)
        override fun request() = original
        override fun isExecuted() = executed.get()
        override fun isCanceled() = cancelled.get()
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = RoutedCall(original)
        override fun cancel() { cancelled.set(true); active.get()?.cancel() }
        override fun execute(): Response {
            check(executed.compareAndSet(false, true)) { "Already executed" }
            return follow()
        }
        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true)) { "Already executed" }
            executor.execute {
                val response = try { follow() } catch (e: IOException) { responseCallback.onFailure(this, e); return@execute }
                responseCallback.onResponse(this, response)
            }
        }
        private fun follow(): Response {
            var request = original.newBuilder().removeHeader("Proxy-Authorization").build()
            repeat(21) { hop ->
                if (cancelled.get()) throw IOException("Cancelled")
                var result: Response? = null
                var last: IOException? = null
                val targets = resolver.requestTargets(context, request.url.toString(), sessionTarget)
                for (target in targets.filterNot { failures.contains(request.url, it) }) {
                    try {
                        val call = pool.client(target).newCall(request)
                        active.set(call)
                        if (cancelled.get()) call.cancel()
                        val received = call.execute()
                        if (received.code in listOf(408, 429) || received.code in 500..599) {
                            failures.record(request.url, target); onFailure(target)
                            if (target != targets.last()) { received.close(); continue }
                        }
                        val body = received.body
                        result = if (body == null) received else received.newBuilder().body(object : ResponseBody() {
                            private val input = object : ForwardingSource(body.source()) {
                                override fun read(sink: okio.Buffer, byteCount: Long): Long = try { super.read(sink, byteCount) }
                                    catch (error: IOException) { if (!cancelled.get()) { failures.record(request.url, target); onFailure(target) }; throw error }
                            }.buffer()
                            override fun contentType() = body.contentType()
                            override fun contentLength() = body.contentLength()
                            override fun source() = input
                        }).build()
                        break
                    } catch (error: IOException) {
                        if (cancelled.get()) throw error
                        failures.record(request.url, target); onFailure(target); last = error
                    }
                }
                val response = result ?: throw (last ?: IOException("No healthy route for media host"))
                if (cancelled.get()) { response.close(); throw IOException("Cancelled") }
                if (response.code !in listOf(301, 302, 303, 307, 308)) return response
                val location = response.header("Location")
                val next = location?.let { request.url.resolve(it) }
                response.close()
                if (next == null || hop == 20 || next.username.isNotEmpty() || next.password.isNotEmpty()) throw IOException("Invalid redirect")
                val builder = request.newBuilder().url(next).removeHeader("Proxy-Authorization")
                if (request.url.host != next.host || request.url.port != next.port || request.url.scheme != next.scheme) {
                    builder.removeHeader("Authorization").removeHeader("Cookie")
                }
                if (response.code == 303 || (response.code in listOf(301, 302) && request.method == "POST")) builder.get()
                request = builder.build()
            }
            throw IOException("Too many redirects")
        }
    }
    companion object {
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "tvatlas-media-request").apply { isDaemon = true } }
    }
}
