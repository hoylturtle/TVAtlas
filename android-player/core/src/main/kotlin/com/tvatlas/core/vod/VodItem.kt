package com.tvatlas.core.vod

import com.tvatlas.core.model.httpUri
import com.tvatlas.core.model.stableId
import kotlinx.serialization.Serializable

/** Temporary URLs stay in memory; only the opaque content key is used in resume storage. */
class VodItem private constructor(val url: String, val title: String, val key: String) {
    override fun toString() = "VodItem(url=[redacted], key=[redacted])"
    companion object {
        fun episode(url: String, title: String, identity: String): VodItem {
            require(identity.isNotBlank() && identity.length <= 1024)
            val validated = direct(url, title)
            return VodItem(validated.url, validated.title, stableId("episode\u0000$identity"))
        }
        fun direct(url: String, title: String = ""): VodItem {
            require(url.length <= 8192) { "视频地址过长" }
            val clean = url.trim()
            val uri = httpUri(clean) ?: throw IllegalArgumentException("请输入不含用户名或密码的 HTTP(S) 视频直链")
            require(uri.host.lowercase() !in setOf("pan.quark.cn", "pan.baidu.com", "115cdn.com", "115.com")) {
                "这是网盘网页地址，请使用分享导入；尚未解析的视频直链不能直接播放"
            }
            val label = title.trim().ifEmpty { "视频点播" }
            require(label.length <= 80 && label.none { it.isISOControl() }) { "视频名称最多 80 个字符，不能包含控制字符" }
            return VodItem(clean, label, stableId("vod\u0000$clean"))
        }
    }
}

@Serializable data class VodBookmark(val positionMs: Long, val durationMs: Long)
object VodResume {
    fun position(bookmark: VodBookmark?, durationMs: Long): Long {
        if (bookmark == null || durationMs <= 0 || bookmark.positionMs < 5000) return 0
        val position = bookmark.positionMs.coerceIn(0, durationMs)
        // A short clip still has a useful resume point; completion threshold is proportional.
        val endMargin = minOf(30000L, durationMs / 20)
        return if (durationMs - position <= endMargin) 0 else position
    }
}
