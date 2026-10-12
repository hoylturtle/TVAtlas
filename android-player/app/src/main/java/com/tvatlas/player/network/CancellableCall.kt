package com.tvatlas.player.network

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response

/** Cancellation closes a blocked socket, including while response bodies are being read. */
internal suspend fun <T> Call.readCancellable(read: suspend (Response) -> T): T = coroutineScope {
    val call = this@readCancellable
    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try { withContext(Dispatchers.IO) { call.execute().use { response -> read(response) } } }
    catch (error: java.io.IOException) {
        currentCoroutineContext().ensureActive()
        throw error
    }
    finally { cancellation.cancel() }
}
