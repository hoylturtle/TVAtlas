package com.tvatlas.player.translation

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ModelDownloadSnapshot(val downloaded: Long, val total: Long, val active: Boolean, val waiting: Boolean)
/** Public Android DownloadManager counters only; no reflection into ML Kit private state. */
internal class ModelDownloadProgress(context: Context) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    suspend fun read(since: Long): ModelDownloadSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val dm = manager ?: return@runCatching null
            var bytes = 0L; var total = 0L; var unknown = false; var found = false; var active = false; var waiting = false
            dm.query(DownloadManager.Query())?.use { cursor ->
                val uri = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
                val done = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val size = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val status = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val modified = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                while (cursor.moveToNext()) {
                    val value = Uri.parse(cursor.getString(uri).orEmpty()); val host = value.host.orEmpty().lowercase()
                    val path = value.path.orEmpty().lowercase()
                    val modelHost = listOf("gvt1.com", "googleapis.com", "gstatic.com").any { host == it || host.endsWith(".$it") }
                    if (!modelHost || !(path.contains("model") || path.contains("translate") || path.contains("mlkit"))) continue
                    val state = cursor.getInt(status)
                    val running = state in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
                    if (!running && cursor.getLong(modified) < since) continue
                    found = true; active = active || running
                    waiting = waiting || state == DownloadManager.STATUS_PAUSED || state == DownloadManager.STATUS_PENDING
                    bytes += cursor.getLong(done).coerceAtLeast(0)
                    val length = cursor.getLong(size)
                    if (length <= 0) unknown = true else total += length
                }
            }
            if (!found) null else ModelDownloadSnapshot(bytes, if (unknown) 0 else total, active, waiting)
        }.getOrNull()
    }
}
