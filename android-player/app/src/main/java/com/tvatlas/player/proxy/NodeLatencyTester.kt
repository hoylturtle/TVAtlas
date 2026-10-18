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

data class NodeTestProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val activeIds: Set<String> = emptySet(),
    val running: Boolean = false,
    val samplesPerNode: Int = 3,
) {
    val succeeded: Int get() = completed - failed
    val remaining: Int get() = total - completed
}

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
        try {
            val token = synchronized(this) { generation }
            val sample = withContext(Dispatchers.IO) { NodeHealth(probe(RouteTarget.DIRECT), System.currentTimeMillis()) }
            synchronized(this) { if (token == generation) _direct.value = sample }
        }
        finally { directMutex.unlock() }
    }
    private val _testing = MutableStateFlow(false)
    val testing = _testing.asStateFlow()
    private val _progress = MutableStateFlow(NodeTestProgress())
    val progress = _progress.asStateFlow()
    private var generation = 0L
    @Synchronized fun invalidate() { generation++; _health.value = emptyMap(); _direct.value = null; _progress.value = NodeTestProgress() }
    @Synchronized fun failed(target: RouteTarget) {
        target.profile?.let { _health.value = _health.value + (it to NodeHealth(null, System.currentTimeMillis())) }
    }
    @Synchronized private fun started(id: String, token: Long) {
        if (token == generation) _progress.value = _progress.value.copy(activeIds = _progress.value.activeIds + id)
    }
    @Synchronized private fun save(id: String, sample: NodeHealth, token: Long) {
        if (token != generation) return
        _health.value = _health.value + (id to sample)
        val previous = _progress.value
        _progress.value = previous.copy(completed = previous.completed + 1,
            failed = previous.failed + if (sample.delayMs == null) 1 else 0, activeIds = previous.activeIds - id)
    }
    @Synchronized private fun finish(token: Long) {
        if (token == generation) _progress.value = _progress.value.copy(running = false, activeIds = emptySet())
    }
    suspend fun measure(profiles: List<ProxyProfile>, staleOnly: Boolean = false) {
        if (!mutex.tryLock()) return
        _testing.value = true
        val token = synchronized(this) { generation }
        try {
            val candidates = profiles.distinctBy { it.id }.filter { it.enabled && !(it.subscriptionId != null && com.tvatlas.core.subscription.ClashSubscription.isNoticeName(it.name)) && (!staleOnly || _health.value[it.id]?.fresh(System.currentTimeMillis()) != true) }
            synchronized(this) {
                if (token == generation) _progress.value = NodeTestProgress(total = candidates.size, running = true, samplesPerNode = if (staleOnly) 1 else 3)
            }
            val semaphore = Semaphore(4)
            suspend fun runBatch() {
                coroutineScope { candidates.map { profile -> launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        started(profile.id, token)
                        val values = mutableListOf<Long>()
                        withTimeoutOrNull(if (staleOnly) 5_000L else 15_000L) {
                            repeat(if (staleOnly) 1 else 3) {
                                val delay = try { probe(RouteTarget.proxy(profile.id)) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { null }
                                if (delay != null) values.add(delay)
                            }
                        }
                        save(profile.id, NodeHealth(values.sorted().let { it.getOrNull(it.size / 2) }, System.currentTimeMillis()), token)
                    }
                } }.joinAll() }
            }
            if (staleOnly) withTimeoutOrNull(10_000L) { runBatch() } else runBatch()
        } finally { finish(token); _testing.value = false; mutex.unlock() }
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
