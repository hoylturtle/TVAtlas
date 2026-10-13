package com.tvatlas.player.proxy

import com.tvatlas.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** HTTPS response latency through the actual proxy, including DNS, TCP and TLS; not ICMP ping. */
class NodeLatencyTester(private val client: (RouteTarget) -> OkHttpClient,
    private val endpoint: String = "https://www.gstatic.com/generate_204") {
    private val mutex = Mutex()
    private val _health = MutableStateFlow<Map<String, NodeHealth>>(emptyMap())
    val health = _health.asStateFlow()
    private val _direct = MutableStateFlow<NodeHealth?>(null)
    val direct = _direct.asStateFlow()
    private val directMutex = Mutex()
    suspend fun measureDirect() {
        if (_direct.value?.fresh(System.currentTimeMillis()) == true || !directMutex.tryLock()) return
        try { _direct.value = withContext(Dispatchers.IO) { NodeHealth(probe(RouteTarget.DIRECT), System.currentTimeMillis()) } }
        finally { directMutex.unlock() }
    }
    private val _testing = MutableStateFlow(false)
    val testing = _testing.asStateFlow()
    private var generation = 0L
    @Synchronized fun invalidate() { generation++; _health.value = emptyMap() }
    @Synchronized fun failed(target: RouteTarget) {
        target.profile?.let { _health.value = _health.value + (it to NodeHealth(null, System.currentTimeMillis())) }
    }
    @Synchronized private fun save(id: String, sample: NodeHealth, token: Long) { if (token == generation) _health.value = _health.value + (id to sample) }
    suspend fun measure(profiles: List<ProxyProfile>, staleOnly: Boolean = false) {
        if (!mutex.tryLock()) return
        _testing.value = true
        try {
            val token = synchronized(this) { generation }
            val candidates = profiles.filter { it.enabled && (!staleOnly || _health.value[it.id]?.fresh(System.currentTimeMillis()) != true) }
            val semaphore = Semaphore(4)
            withTimeoutOrNull(if (staleOnly) 10_000L else 120_000L) {
                coroutineScope { candidates.map { profile -> launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val values = mutableListOf<Long>()
                        repeat(if (staleOnly) 1 else 3) {
                            val delay = probe(RouteTarget.proxy(profile.id))
                            if (delay != null) values.add(delay)
                        }
                        save(profile.id, NodeHealth(values.sorted().let { it.getOrNull(it.size / 2) }, System.currentTimeMillis()), token)
                    }
                } }.joinAll() }
            }
        } finally { _testing.value = false; mutex.unlock() }
    }
    private suspend fun probe(target: RouteTarget): Long? {
        val network = try { client(target).newBuilder().callTimeout(4, TimeUnit.SECONDS).connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() }
        catch (_: IOException) { return null }
        val started = System.nanoTime() // Exclude local core startup; compare actual network responses.
        return suspendCancellableCoroutine { continuation ->
            val call = network.newCall(Request.Builder().url(endpoint).header("Cache-Control", "no-cache").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val delay = response.use { if (it.code == 204) ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1) else null }
                    if (continuation.isActive) continuation.resume(delay)
                }
            })
        }
    }
}
