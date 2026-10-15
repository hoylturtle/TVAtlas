package com.tvatlas.core.cloud

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.Serializable

@Serializable enum class CloudProvider(val label: String) { QUARK("夸克"), BAIDU("百度网盘"), PAN115("115") }

/** Sensitive capability: its link and code must never appear in log/toString output. */
@Serializable data class CloudShare(val provider: CloudProvider, val shareId: String, val code: String = "", val name: String = provider.label + "分享") {
    init {
        require(shareId.matches(Regex("[A-Za-z0-9_-]{5,100}")))
        require(code.isEmpty() || code.matches(Regex("[A-Za-z0-9]{4}")))
        require(name.length <= 80 && name.none { it.isISOControl() })
    }
    val key: String get() = provider.name + ":" + shareId
    fun webUrl(): String = when (provider) {
        CloudProvider.QUARK -> "https://pan.quark.cn/s/$shareId" + if (code.isEmpty()) "" else "?pwd=$code"
        CloudProvider.BAIDU -> "https://pan.baidu.com/s/$shareId" + if (code.isEmpty()) "" else "?pwd=$code"
        CloudProvider.PAN115 -> "https://115cdn.com/s/$shareId" + if (code.isEmpty()) "" else "?password=$code"
    }
    override fun toString() = "CloudShare(provider=$provider, link=[redacted], code=[redacted])"
}

object CloudShareParser {
    private val urls = Regex("https?://[^\\s<>\"，。；）)]+", RegexOption.IGNORE_CASE)
    private val textCode = Regex("(?:提取码|提取碼|访问码|訪問碼|密码|密碼)\\s*[:：]?\\s*([A-Za-z0-9]{4})(?![A-Za-z0-9])")
    fun parse(text: String, explicitCode: String = "", name: String = ""): CloudShare {
        require(text.length <= 8192 && name.length <= 80 && name.none { it.isISOControl() }) { "分享内容过长或名称无效" }
        val found = urls.findAll(text).mapNotNull { match ->
            val uri = runCatching { URI(match.value.trimEnd('#', '&')) }.getOrNull() ?: return@mapNotNull null
            val provider = when (uri.host?.lowercase()) {
                "pan.quark.cn" -> CloudProvider.QUARK
                "pan.baidu.com" -> CloudProvider.BAIDU
                "115.com", "www.115.com", "115cdn.com", "www.115cdn.com", "anxia.com", "www.anxia.com" -> CloudProvider.PAN115
                else -> return@mapNotNull null
            }
            require(uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.port == -1) { "请使用网盘官方 HTTPS 分享地址" }
            val id = Regex("^/s/([A-Za-z0-9_-]{5,100})/?$").matchEntire(uri.path)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("分享地址格式不受支持，请粘贴完整分享链接")
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val kv = pair.split('=', limit = 2)
                if (kv.size != 2) null else URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8")
            }
            val codes = params.filter { it.first in listOf("pwd", "password", "receive_code") }.map { it.second }.filter { it.isNotBlank() }.distinct()
            require(codes.size <= 1) { "链接包含不同的提取码，请检查" }
            CloudShare(provider, id, codes.firstOrNull().orEmpty())
        }.distinct().toList()
        require(found.size == 1) { if (found.isEmpty()) "未识别到夸克、百度网盘或 115 官方分享链接" else "一次只导入一个分享链接" }
        val base = found.single()
        val code = explicitCode.trim().ifEmpty {
            val values = (listOf(base.code) + textCode.findAll(text).map { it.groupValues[1] }).filter { it.isNotEmpty() }.distinct()
            require(values.size <= 1) { "分享文字与链接中的提取码不同，请单独填写" }
            values.firstOrNull().orEmpty()
        }
        require(code.isEmpty() || code.matches(Regex("[A-Za-z0-9]{4}"))) { "提取码应为 4 位字母或数字" }
        val quotedName = Regex("[「【](.{1,80}?)[」】]").find(text)?.groupValues?.get(1)
        return base.copy(code = code, name = name.trim().ifEmpty { quotedName ?: base.name })
    }
}

@Serializable data class CloudFile(val id: String, val name: String, val directory: Boolean, val size: Long = 0) {
    val video: Boolean get() = !directory && name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "mov", "m4v", "ts", "m2ts", "webm", "avi")
}
data class CloudDirectory(val files: List<CloudFile>, val nextOffset: Int? = null)
enum class CloudFailure { NEED_LOGIN, WRONG_CODE, EXPIRED, UNSUPPORTED, NETWORK, LIMITED }
class CloudException(val kind: CloudFailure, message: String) : java.io.IOException(message)

/** Folder first, then natural episode order (E2 before E10), without parsing unbounded numbers. */
fun Iterable<CloudFile>.episodeOrder(): List<CloudFile> {
    val parts = Regex("[0-9]+|[^0-9]+")
    return sortedWith { a, b ->
        if (a.directory != b.directory) if (a.directory) -1 else 1
        else {
            val left = parts.findAll(a.name).map { it.value }.toList()
            val right = parts.findAll(b.name).map { it.value }.toList()
            var compared = 0
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]; val y = right[i]
                compared = if (x[0].isDigit() && y[0].isDigit()) {
                    val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
                    xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
                } else x.compareTo(y, ignoreCase = true)
                if (compared != 0) break
            }
            if (compared != 0) compared else left.size.compareTo(right.size)
        }
    }
}
