package com.tvatlas.player.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded app-private diagnostic history. Never accepts request/response bodies or headers. */
class DebugLog(private val file: File, private val environment: String) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "tvatlas-debug-log").apply { isDaemon = true } }
    private val lock = Any()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()
    private var closed = false
    private var generation = 0L
    private var writeScheduled = false
    init {
        worker.execute {
            val saved = runCatching {
                if (file.isFile && file.length() <= MAX_BYTES) file.readLines(Charsets.UTF_8).map(::sanitize) else emptyList()
            }.getOrDefault(emptyList())
            synchronized(lock) { if (generation == 0L) _lines.value = bounded(saved + _lines.value) }
        }
        event("INFO", "APP", environment)
    }
    fun event(level: String, stage: String, message: String) = synchronized(lock) {
        if (closed) return@synchronized
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val line = sanitize("$time ${level.take(8)} ${stage.take(32)} $message")
        _lines.value = bounded(_lines.value + line)
        persistLater()
    }
    fun error(stage: String, error: Throwable) {
        // Exception messages often include URLs, account names or server response bodies.
        val causes = generateSequence(error) { it.cause }.take(6).joinToString(" -> ") { it.javaClass.simpleName }
        val frames = error.stackTrace.take(5).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        event("ERROR", stage, "$causes frames=$frames")
    }
    fun snapshot(): String = "TVAtlas debug log\n${sanitize(environment)}\nURLs, headers and bodies omitted; no automatic upload.\n\n" +
        synchronized(lock) { _lines.value.joinToString("\n") } + "\n"
    fun clear() = synchronized(lock) {
        generation++
        _lines.value = emptyList()
        persistLater()
    }
    private fun persistLater() {
        if (writeScheduled) return
        writeScheduled = true
        worker.execute {
            while (true) {
                val snapshot = synchronized(lock) { _lines.value }
                runCatching {
                    file.parentFile?.mkdirs()
                    val temporary = File(file.parentFile, file.name + ".tmp")
                    temporary.writeText(snapshot.joinToString("\n", postfix = if (snapshot.isEmpty()) "" else "\n"), Charsets.UTF_8)
                    if (!temporary.renameTo(file)) temporary.delete()
                }
                val complete = synchronized(lock) {
                    if (_lines.value == snapshot) { writeScheduled = false; true } else false
                }
                if (complete) break
            }
        }
    }
    override fun close() = synchronized(lock) { closed = true; worker.shutdown() }
    internal fun awaitWrites(): Boolean = worker.submit {}.get(5, TimeUnit.SECONDS).let { true }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        const val MAX_LINES = 500
        private val urls = Regex("(?i)(?:https?|socks5?|ss|vmess|vless|trojan)://[^\\s]+")
        private val credentials = Regex("(?i)(authorization|proxy-authorization|x-user-token|cookie|set-cookie|password|passwd|token|sig|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+")
        private val jwt = Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
        private val opaque = Regex("[A-Za-z0-9_+/=-]{80,}")
        fun sanitize(value: String): String = value
            .replace(Regex("(?im)(authorization|proxy-authorization|x-user-token|cookie|set-cookie)\\s*:\\s*[^\\r\\n]*"), "$1=[redacted]")
            .replace(urls, "[URL omitted]")
            .replace(Regex("(?i)(authorization|proxy-authorization)\\s*[:=]\\s*Bearer\\s+[^\\s]+"), "$1=[redacted]")
            .replace(credentials, "$1=[redacted]").replace(jwt, "[token redacted]")
            .replace(opaque, "[opaque data redacted]").replace(Regex("[\\r\\n\\t\\p{Cntrl}]"), " ").take(1000)
        private fun bounded(input: List<String>): List<String> {
            val result = input.takeLast(MAX_LINES).toMutableList()
            var size = result.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 1 }
            while (size > MAX_BYTES && result.isNotEmpty()) size -= result.removeAt(0).toByteArray(Charsets.UTF_8).size + 1
            return result.toList()
        }
    }
}
