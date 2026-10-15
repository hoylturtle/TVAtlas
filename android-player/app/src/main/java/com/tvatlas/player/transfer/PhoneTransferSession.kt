package com.tvatlas.player.transfer

import com.tvatlas.core.model.httpUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

enum class TransferKind(val title: String, val defaultName: String) {
    PLAYLIST("频道订阅", "手机频道订阅"), PROXY("Clash / Mihomo 代理订阅", "手机代理订阅"), CLOUD("网盘分享", "手机网盘分享"), VIDEO("视频直链", "手机视频")
}
data class TransferInput(val name: String, val url: String)
data class TransferState(val active: Boolean = true, val received: TransferInput? = null, val error: String? = null)

/** A bounded, single-use local HTTP receiver. Never logs or persists submitted addresses. */
class PhoneTransferSession(address: InetAddress, val kind: TransferKind, lifetimeMillis: Long = 300_000) : AutoCloseable {
    init { require(address is Inet4Address); require(lifetimeMillis > 0) }
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 4, address).apply { soTimeout = 500 }
    private val connection = AtomicReference<Socket?>()
    private val deadline = System.nanoTime() + lifetimeMillis * 1_000_000
    private val _state = MutableStateFlow(TransferState())
    val state = _state.asStateFlow()
    private val origin = "http://${address.hostAddress}:${server.localPort}"
    val pageUrl = "$origin/$token"
    val remainingSeconds: Int get() = ((deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000_000).toInt()
    private val worker = Thread({ serve() }, "TVAtlas-phone-input").apply { isDaemon = true; start() }

    private fun serve() {
        try {
            while (!server.isClosed && _state.value.active) {
                if (System.nanoTime() >= deadline) {
                    _state.value = TransferState(active = false, error = "二维码已过期，请重新生成")
                    break
                }
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                connection.set(socket)
                socket.use {
                    it.soTimeout = 1500
                    try { handle(it) } catch (_: Exception) { runCatching { respond(it, 400, "请求无效，请重新扫码", false) } }
                }
                connection.compareAndSet(socket, null)
            }
        } catch (_: Exception) {
            if (!server.isClosed) _state.update { it.copy(active = false, error = "临时页面连接中断，请重新生成") }
        } finally { close() }
    }
    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                require(next >= 0 && bytes.size() < 4096)
                if (next == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
                bytes.write(next)
            }
        }
        val request = line().split(' ')
        require(request.size == 3 && request[2] in listOf("HTTP/1.1", "HTTP/1.0"))
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val value = line(); total += value.length
            require(total <= 8192)
            if (value.isEmpty()) break
            val separator = value.indexOf(':'); require(separator > 0)
            val key = value.substring(0, separator).lowercase()
            require(headers.put(key, value.substring(separator + 1).trim()) == null)
        }
        if (System.nanoTime() >= deadline || !_state.value.active) { respond(socket, 410, "二维码已失效，请重新扫码", false); return }
        if (request[1] != "/$token" || headers["host"] != origin.removePrefix("http://")) {
            respond(socket, 404, "页面不存在，请使用电视显示的二维码", false); return
        }
        if (headers["origin"] != null && headers["origin"] != origin) { respond(socket, 403, "请从扫码页面发送", false); return }
        when (request[0]) {
            "GET" -> respond(socket, 200, page(), true)
            "POST" -> {
                if (headers["transfer-encoding"] != null || headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded") {
                    respond(socket, 400, "请求格式无效", false); return
                }
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..32768) { respond(socket, 413, "输入内容过长", false); return }
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(bytes, read, length - read); require(n > 0); read += n }
                val fields = mutableMapOf<String, String>()
                String(bytes, Charsets.UTF_8).split('&').forEach {
                    val pair = it.split('=', limit = 2); require(pair.size == 2)
                    val key = URLDecoder.decode(pair[0], "UTF-8")
                    require(key in listOf("name", "url") && fields.put(key, URLDecoder.decode(pair[1], "UTF-8")) == null)
                }
                val name = fields["name"].orEmpty().trim()
                val url = fields["url"].orEmpty().trim()
                if (name.length > 80 || name.any { it.isISOControl() } || url.length > 8192 || (when (kind) {
                    TransferKind.CLOUD -> runCatching { com.tvatlas.core.cloud.CloudShareParser.parse(url) }.isFailure
                    TransferKind.VIDEO -> runCatching { com.tvatlas.core.vod.VodItem.direct(url, name) }.isFailure
                    else -> httpUri(url) == null
                })) {
                    respond(socket, 400, if (kind == TransferKind.CLOUD) "请粘贴一个夸克、百度或 115 分享链接及提取码，名称不超过 80 字" else if (kind == TransferKind.VIDEO) "请填写有效的 HTTP(S) 视频直链，不能填写网盘分享网页" else "请填写有效的 HTTP(S) 订阅地址，名称不超过 80 字", false); return
                }
                if (System.nanoTime() >= deadline || !_state.value.active) { respond(socket, 410, "二维码已失效，请重新扫码", false); return }
                // Flush the phone acknowledgement before UI disposal closes the receiver.
                respond(socket, 200, "已发送到电视，请在电视上确认添加。", false)
                _state.value = TransferState(active = false, received = TransferInput(name, url))
            }
            else -> respond(socket, 405, "不支持的请求", false)
        }
    }
    private fun respond(socket: Socket, status: Int, text: String, html: Boolean) {
        val body = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status Response\r\nContent-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
    private fun page() = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TVAtlas 手机输入</title><style>body{font:17px system-ui;background:#101217;color:#eee;margin:0;padding:24px;max-width:520px;margin:auto}h1{font-size:25px}label{display:block;margin:24px 0 8px}input,textarea,button{box-sizing:border-box;width:100%;font:inherit;border-radius:10px;padding:14px;border:1px solid #596070;background:#1d222b;color:#fff}textarea{height:140px}button{margin-top:24px;background:#b5ccff;color:#162a4b;border:0}small{color:#adb5c4}#result{line-height:1.6}</style>
<h1>TVAtlas · ${kind.title}</h1><p>${if (kind == TransferKind.CLOUD) "粘贴完整网盘分享文字，发送后在电视上确认保存。" else if (kind == TransferKind.VIDEO) "粘贴视频直链，发送后在电视上确认播放。" else "粘贴手机上的订阅地址，发送后在电视上确认添加。"}</p><small>临时页面有效 5 分钟，仅通过当前局域网传输。</small>
<form id="form"><label for="name">名称（可选）</label><input id="name" name="name" maxlength="80" autocomplete="off" placeholder="给订阅起个名字"><label for="url">${if (kind == TransferKind.CLOUD) "完整分享文字或链接" else if (kind == TransferKind.VIDEO) "视频直链" else "订阅地址"}</label><textarea id="url" name="url" maxlength="8192" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" required placeholder="${if (kind == TransferKind.CLOUD) "粘贴分享链接和提取码" else if (kind == TransferKind.VIDEO) "在这里粘贴 HTTP(S) 视频直链" else "在这里粘贴 HTTP(S) 订阅地址"}"></textarea><button id="send">发送到电视</button></form><p id="result" role="status"></p>
<script nonce="$token">document.getElementById('form').addEventListener('submit',async function(e){e.preventDefault();const b=document.getElementById('send'),r=document.getElementById('result');b.disabled=true;r.textContent='正在发送…';try{const res=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({name:document.getElementById('name').value,url:document.getElementById('url').value})});r.textContent=await res.text();if(res.ok){this.reset();this.hidden=true}else b.disabled=false}catch(e){r.textContent='连接失败。请确认手机和电视在同一局域网，二维码仍有效，再重新发送。';b.disabled=false}});</script></html>"""
    override fun close() {
        runCatching { server.close() }
        runCatching { connection.getAndSet(null)?.close() }
        _state.update { if (it.active) it.copy(active = false) else it }
    }
}
