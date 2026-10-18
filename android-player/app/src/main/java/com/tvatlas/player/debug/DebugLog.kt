package com.tvatlas.player.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LogCategory(val label: String, val fileKey: String) {
    ALL("全部", "all"), STARTUP("启动", "startup"), PLAYBACK("播放", "playback"), INTERACTION("操作", "interaction");
    fun accepts(line: String): Boolean {
        val stage = line.split(' ', limit = 7).getOrNull(4).orEmpty()
        return when (this) {
            ALL -> true
            STARTUP -> stage == "APP" || stage.startsWith("STARTUP") || stage == "CHANNEL_INDEX" || stage == "LIFECYCLE"
            INTERACTION -> stage.startsWith("INPUT") || stage in setOf("NAVIGATION", "MAIN_STALL", "MAIN_RECOVERED")
            PLAYBACK -> stage in setOf("PLAY", "PLAYER", "ROUTE", "SOURCE", "HTTP", "SESSION", "SESSION_PAIR", "SESSION_BOOTSTRAP", "CHECKOUT", "CHECKOUT_URL", "CHECKOUT_FORMAT", "FAILOVER", "MANIFEST", "MEDIA", "DRM_CONFIG", "DRM_LICENSE", "SUBTITLE", "TRANSLATION", "VOD_PLAY", "VOD_PLAYER", "VOD_HTTP", "VOD_NETWORK", "NODE_TEST")
        }
    }
}

/** Bounded app-private diagnostic history. Never accepts request/response bodies or headers. */
class DebugLog(private val file: File, private val environment: String) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "tvatlas-debug-log").apply { isDaemon = true } }
    private val lock = Any()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()
    private val byteSizes = java.util.ArrayDeque<Int>()
    private var totalBytes = 0L
    private val startup = ArrayList<String>()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private var closed = false
    private var generation = 0L
    private var writeScheduled = false
    init {
        worker.execute {
            val saved = runCatching {
                if (file.isFile && file.length() <= MAX_BYTES) file.readLines(Charsets.UTF_8).map(::sanitize) else emptyList()
            }.getOrDefault(emptyList())
            synchronized(lock) { if (generation == 0L) {
                _lines.value = bounded(saved + _lines.value)
                byteSizes.clear(); totalBytes = 0
                _lines.value.forEach { line -> val size = line.toByteArray(Charsets.UTF_8).size + 1; byteSizes.addLast(size); totalBytes += size }
            } }
        }
        event("INFO", "APP", environment)
    }
    fun event(level: String, stage: String, message: String) = synchronized(lock) {
        if (closed) return@synchronized
        val time = timestamp.format(Date())
        val line = sanitize("$time ${level.take(8)} ${stage.take(32)} thread=${Thread.currentThread().name.take(32)} $message")
        if ((stage == "APP" || stage.startsWith("STARTUP") || stage == "CHANNEL_INDEX" || stage == "LIFECYCLE") && startup.size < 64) startup.add(line)
        val size = line.toByteArray(Charsets.UTF_8).size + 1
        byteSizes.addLast(size); totalBytes += size
        var removed = 0
        while (byteSizes.size > MAX_LINES || totalBytes > MAX_BYTES) { totalBytes -= byteSizes.removeFirst(); removed++ }
        _lines.value = (_lines.value + line).drop(removed)
        persistLater()
    }
    fun error(stage: String, error: Throwable) {
        // Exception messages often include URLs, account names or server response bodies.
        val causes = generateSequence(error) { it.cause }.take(6).joinToString(" -> ") { it.javaClass.simpleName }
        val frames = error.stackTrace.take(5).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        event("ERROR", stage, "$causes frames=$frames")
    }
    fun categoryLines(category: LogCategory): List<String> = synchronized(lock) {
        (if (category == LogCategory.STARTUP) (startup + _lines.value).distinct() else _lines.value).filter(category::accepts)
    }
    fun snapshot(category: LogCategory = LogCategory.ALL): String {
        if (category != LogCategory.ALL) return "TVAtlas ${category.label}日志\n${sanitize(environment)}\nURLs, headers and bodies omitted; no automatic upload.\n\n" + categoryLines(category).joinToString("\n") + "\n"

        val (rows, launch) = synchronized(lock) { _lines.value to startup.toList() }
        val current = rows.indexOfLast { it.contains(" INFO APP ") }.coerceAtLeast(0)
        return "TVAtlas debug log\n${sanitize(environment)}\nURLs, headers and bodies omitted; no automatic upload.\n\n" +
            "--- Startup summary / 本次启动摘要 ---\n" + launch.joinToString("\n") + "\n\n" +
            "--- Current launch / 本次启动 ---\n" + rows.drop(current).joinToString("\n") + "\n" +
            if (current > 0) "\n--- Earlier launches / 历史记录（可能来自旧版本）---\n" + rows.take(current).joinToString("\n") + "\n" else ""
    }
    fun clear() = synchronized(lock) {
        generation++
        _lines.value = emptyList(); byteSizes.clear(); totalBytes = 0; startup.clear()
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
