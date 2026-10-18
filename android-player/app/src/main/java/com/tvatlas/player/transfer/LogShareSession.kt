package com.tvatlas.player.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/** Read-only snapshot, LAN-bound, token-gated and short lived. No file paths, uploads or app commands. */
class LogShareSession(address: InetAddress, snapshot: String, lifetimeMillis: Long = 300_000) : AutoCloseable {
    init { require(address is Inet4Address); require(lifetimeMillis in 1..300_000); require(snapshot.toByteArray().size <= 1_100_000) }
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 4, address).apply { soTimeout = 250 }
    private val deadline = System.nanoTime() + lifetimeMillis * 1_000_000
    private val _active = MutableStateFlow(true)
    val active = _active.asStateFlow()
    private val authority = "${address.hostAddress}:${server.localPort}"
    val pageUrl = "http://$authority/$token"
    private val text = snapshot
    @Volatile private var connection: Socket? = null
    val remainingSeconds get() = ((deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000_000).toInt()
    init { Thread(::serve, "TVAtlas-log-share").apply { isDaemon = true; start() } }
    private fun serve() {
        try {
            while (_active.value && System.nanoTime() < deadline) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                connection = socket
                socket.use {
                    it.soTimeout = 1500
                    try { handle(it) } catch (_: Exception) { runCatching { respond(it, 400, "请求无效") } }
                }
                connection = null
            }
        } catch (_: Exception) { /* Closing a dialog closes the listener and current client. */ }
        finally { close() }
    }
    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        var count = 0
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                require(next >= 0 && ++count <= 8192 && bytes.size() < 4096)
                if (next == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(next)
            }
        }
        val request = line().split(' ')
        require(request.size == 3 && request[2] in listOf("HTTP/1.0", "HTTP/1.1"))
        val headers = mutableMapOf<String, String>()
        while (true) {
            val value = line(); if (value.isEmpty()) break
            val separator = value.indexOf(':'); require(separator > 0)
            require(headers.put(value.substring(0, separator).lowercase(), value.substring(separator + 1).trim()) == null)
        }
        if (!_active.value || System.nanoTime() >= deadline) { respond(socket, 410, "二维码已过期"); return }
        val path = request[1]
        if (headers["host"] != authority || path !in listOf("/$token", "/$token/log.txt", "/$token/download")) {
            respond(socket, 404, "请使用电视上显示的二维码"); return
        }
        if (headers["origin"] != null && headers["origin"] != "http://$authority") { respond(socket, 403, "请求来源无效"); return }
        if (request[0] != "GET") { respond(socket, 405, "仅支持查看日志"); return }
        if (path == "/$token") respond(socket, 200, page(), html = true)
        else respond(socket, 200, text, download = path.endsWith("/download"))
    }
    private fun respond(socket: Socket, status: Int, value: String, html: Boolean = false, download: Boolean = false) {
        val body = value.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status Response\r\nContent-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n" +
            (if (download) "Content-Disposition: attachment; filename=\"tvatlas-debug.txt\"\r\n" else "") + "\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
    private fun page(): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TVAtlas 诊断日志</title><style>body{font:16px system-ui;background:#171321;color:#eee;padding:16px}button,a{display:inline-block;padding:12px;margin:4px;background:#7040bc;color:white;border:0;border-radius:8px;font:inherit}textarea{box-sizing:border-box;width:100%;height:65vh;background:#211c30;color:white;font:13px monospace}</style>
<h1>TVAtlas 诊断日志</h1><p>这是生成二维码时的日志快照。关闭电视窗口或五分钟后链接失效；日志仅通过当前局域网传输。</p>
<button id="copy">复制日志</button><a href="/$token/download" download="tvatlas-debug.txt">下载 TXT</a><a href="/$token/log.txt">查看纯文本</a>
<p id="result" role="status"></p><textarea id="log" readonly aria-label="诊断日志">$escaped</textarea>
<script nonce="$token">document.getElementById('copy').onclick=async function(){const t=document.getElementById('log'),r=document.getElementById('result');try{if(navigator.clipboard&&window.isSecureContext)await navigator.clipboard.writeText(t.value);else{t.focus();t.select();t.setSelectionRange(0,t.value.length);if(!document.execCommand('copy'))throw Error()}r.textContent='已复制日志'}catch(e){t.focus();t.select();r.textContent='请长按选中的文字复制，或下载 TXT 文件'}}</script></html>"""
    }
    override fun close() {
        _active.value = false
        runCatching { server.close() }
        runCatching { connection?.close() }
    }
}
