package com.tvatlas.player.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.*
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

internal data class UploadedApk(val file: File, val label: String, val packageName: String, val version: String)
internal data class ApkUploadState(val received: Long = 0, val total: Long = 0, val receiving: Boolean = false,
    val validating: Boolean = false, val ready: UploadedApk? = null, val error: String? = null)

/** One explicit, expiring LAN session. APK bytes are streamed to a private file, never buffered in RAM. */
internal class ApkUploadSession(address: InetAddress, directory: File, private val inspect: (File) -> UploadedApk,
    lifetimeMillis: Long = 600_000, private val maximumBytes: Long = 300L * 1024 * 1024) : AutoCloseable {
    init { require(address is Inet4Address); require(lifetimeMillis in 1..600_000); require(maximumBytes in 1..300L * 1024 * 1024) }
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 4, address).apply { soTimeout = 250 }
    private val authority = "${address.hostAddress}:${server.localPort}"
    private val path = "/$token"
    val pageUrl = "http://$authority$path"
    private val deadline = System.nanoTime() + lifetimeMillis * 1_000_000
    private val folder = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
    private val part = File(folder, "upload.part")
    private val apk = File(folder, "received.apk")
    private val timer = Timer("TVAtlas-apk-expiry", true)
    private val _active = MutableStateFlow(true)
    val active = _active.asStateFlow()
    private val _state = MutableStateFlow(ApkUploadState())
    val state = _state.asStateFlow()
    @Volatile private var connection: Socket? = null
    val remainingSeconds get() = ((deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000_000).toInt()
    init {
        timer.schedule(object : TimerTask() { override fun run() { close() } }, lifetimeMillis)
        Thread(::serve, "TVAtlas-apk-upload").apply { isDaemon = true; start() }
    }
    private fun serve() {
        try {
            while (_active.value) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                connection = socket
                socket.use {
                    it.soTimeout = 30_000
                    try { handle(it) }
                    catch (_: Exception) {
                        if (_active.value) {
                            _state.value = _state.value.copy(receiving = false, validating = false, error = "接收未完成或安装包无效，请重新上传")
                            runCatching { respond(it, 400, "接收未完成或安装包无效，请重试") }
                        }
                    } finally { part.delete() }
                }
                connection = null
            }
        } catch (_: Exception) { /* close() unblocks the listener and active upload. */ }
        finally { close(); part.delete() }
    }
    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        var count = 0
        fun line(): String {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                require(byte >= 0 && ++count <= 8192 && out.size() < 4096)
                if (byte == 10) return out.toString("ISO-8859-1").removeSuffix("\r")
                out.write(byte)
            }
        }
        val request = line().split(' ')
        require(request.size == 3 && request[2] in listOf("HTTP/1.0", "HTTP/1.1"))
        val headers = mutableMapOf<String, String>()
        while (true) {
            val row = line()
            if (row.isEmpty()) break
            val split = row.indexOf(':')
            require(split > 0)
            require(headers.put(row.substring(0, split).lowercase(), row.substring(split + 1).trim()) == null)
        }
        if (!_active.value || System.nanoTime() >= deadline) { respond(socket, 410, "二维码已过期"); return }
        if (headers["host"] != authority || request[1] !in listOf(path, "$path/upload")) { respond(socket, 404, "请使用电视上的二维码"); return }
        if (headers["origin"] != null && headers["origin"] != "http://$authority") { respond(socket, 403, "请求来源无效"); return }
        if (request[0] == "GET" && request[1] == path) { respond(socket, 200, page(), true); return }
        if (request[0] != "POST" || request[1] != "$path/upload") { respond(socket, 405, "请求方式不支持"); return }
        if (_state.value.ready != null) { respond(socket, 409, "已收到安装包，请在电视上处理"); return }
        val length = headers["content-length"]?.toLongOrNull()
        if (headers.containsKey("transfer-encoding") || length == null) { respond(socket, 411, "请使用页面选择 APK 上传"); return }
        if (length !in 1..maximumBytes) { respond(socket, 413, "安装包超过允许大小"); return }
        val name = URLDecoder.decode(headers["x-file-name"].orEmpty(), "UTF-8")
        if (name.length !in 1..512 || !name.endsWith(".apk", true)) { respond(socket, 415, "仅支持单个 APK 文件"); return }
        if (folder.usableSpace < length + 20L * 1024 * 1024) { respond(socket, 507, "电视存储空间不足"); return }
        _state.value = ApkUploadState(total = length, receiving = true)
        var received = 0L
        var lastProgress = 0L
        part.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (received < length) {
                check(_active.value && System.nanoTime() < deadline)
                val size = input.read(buffer, 0, minOf(buffer.size.toLong(), length - received).toInt())
                require(size > 0)
                output.write(buffer, 0, size)
                received += size
                val now = System.nanoTime()
                if (now - lastProgress >= 100_000_000 || received == length) {
                    _state.value = _state.value.copy(received = received)
                    lastProgress = now
                }
            }
        }
        check(_active.value)
        _state.value = _state.value.copy(receiving = false, validating = true)
        try {
            val inspected = inspect(part)
            check(_active.value)
            check(part.renameTo(apk))
            _state.value = ApkUploadState(received = length, total = length, ready = inspected.copy(file = apk))
        } catch (_: Exception) {
            apk.delete()
            _state.value = ApkUploadState(error = "无法识别这个 APK，请确认文件完整且适用于 Android")
            respond(socket, 422, "电视无法识别这个 APK，请检查文件是否完整")
            return
        }
        respond(socket, 200, "安装包已收到。请在电视上确认应用信息并选择安装。")
    }
    private fun respond(socket: Socket, status: Int, text: String, html: Boolean = false) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status Response\r\nContent-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
    private fun page() = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>传安装包到电视</title>
<style>body{max-width:600px;margin:auto;padding:24px;font:16px system-ui;background:#171323;color:#f5f3ff}button,input{font:inherit;margin:12px 0;max-width:100%}button{padding:12px 24px;background:#8b5cf6;border:0;border-radius:12px;color:white}progress{width:100%;height:24px}p{line-height:1.6}</style>
<h1>传安装包到电视</h1><p>选择一个 APK，传输后在电视上确认安装。文件只传到当前电视，不上传云端。二维码十分钟内有效。</p><p>最多 ${maximumBytes / 1048576} MB；暂不支持 XAPK／APKS 分包。传输期间请保持电视窗口打开。</p>
<input type="file" id="file" accept=".apk,application/vnd.android.package-archive" aria-label="选择安装包"><br><button id="send">上传到电视</button><progress id="progress" value="0" max="100"></progress><p id="status" role="status">等待选择 APK</p>
<script nonce="$token">const f=document.getElementById('file'),b=document.getElementById('send'),p=document.getElementById('progress'),s=document.getElementById('status');b.onclick=()=>{const file=f.files[0];if(!file||!file.name.toLowerCase().endsWith('.apk')){s.textContent='请选择单个 APK 文件';return}if(file.size>$maximumBytes){s.textContent='文件过大';return}const x=new XMLHttpRequest();x.open('POST',location.pathname+'/upload');x.setRequestHeader('Content-Type','application/vnd.android.package-archive');x.setRequestHeader('X-File-Name',encodeURIComponent(file.name));b.disabled=true;f.disabled=true;x.upload.onprogress=e=>{if(e.lengthComputable){p.value=e.loaded/e.total*100;s.textContent='已上传 '+Math.floor(p.value)+'%'+(e.loaded===e.total?'，等待电视校验…':'')}};x.onload=()=>{s.textContent=x.responseText;b.disabled=x.status===200;f.disabled=x.status===200};x.onerror=()=>{s.textContent='连接中断，请确认电视窗口仍打开并重新上传';b.disabled=false;f.disabled=false};x.send(file)};</script></html>"""
    override fun close() {
        _active.value = false
        timer.cancel()
        runCatching { server.close() }
        runCatching { connection?.close() }
        // Keep a validated APK for the system installer after this activity loses foreground.
    }
}
