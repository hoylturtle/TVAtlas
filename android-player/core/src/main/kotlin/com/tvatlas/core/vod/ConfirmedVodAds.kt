package com.tvatlas.core.vod

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.security.MessageDigest

/** Compiled, human-verified rules, tied to an entire immutable playlist version. */
data class ConfirmedAdRule(val id: String, val host: String, val entryPath: String, val mediaPath: String,
    val masterSha256: String?, val mediaSha256: String, val segmentCount: Int, val blocks: List<IntRange>)
data class ConfirmedAdMatch(val ruleId: String, val ranges: List<VodSkipRange>)
data class ConfirmedAdInspection(val relevant: Boolean, val match: ConfirmedAdMatch? = null)
data class VodPlaylistSegment(val start: BigDecimal, val duration: BigDecimal, val uri: String, val discontinuity: Boolean)
data class VodPlaylistSnapshot(val fingerprint: String, val segments: List<VodPlaylistSegment>, val duration: BigDecimal)

object ConfirmedVodAds {
    const val MAX_BYTES = 1024 * 1024
    val rules = listOf(ConfirmedAdRule(
        "lz-earth2-2023-v1", "hd.lz-cdn18.com", "/20230414/1866_7d8603d9/index.m3u8", "/20230414/1866_7d8603d9/2000k/hls/mixed.m3u8",
        "33afecc87f4a78ef01c7349a5b7c4aea4a8ca7f98dfffe20615c0678c4ee781d",
        "9623af2727b853e1bb8cad6d908ea7485a5dd19d8a5b1c90e41196fa97f50bb0", 2618,
        listOf(75..81, 502..508, 959..965)))
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    internal fun lines(text: String): List<String>? {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).take(40001).toList()
            .takeIf { it.size <= 40000 && it.firstOrNull() == "#EXTM3U" }
    }
    fun masterFingerprint(text: String): String? = lines(text)?.let { sha256(it.joinToString("\n", postfix = "\n")) }
    internal fun uri(value: String): URI? = runCatching { URI(value).normalize() }.getOrNull()?.takeIf {
        it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null
    }
    internal fun endpoint(uri: URI): String {
        val scheme = uri.scheme.lowercase()
        val port = uri.port.takeUnless { it == -1 || it == if (scheme == "https") 443 else 80 }?.let { ":$it" }.orEmpty()
        return "$scheme://${uri.host.lowercase()}$port${uri.rawPath.ifEmpty { "/" }}"
    }
    /** No heuristics: incomplete/live, encrypted, byte-range and fMP4 playlists are not recognized. */
    fun snapshot(url: String, text: String): VodPlaylistSnapshot? { return runCatching {
        val base = uri(url) ?: return null
        val rows = lines(text) ?: return null
        if ("#EXT-X-ENDLIST" !in rows || rows.any { row ->
            row.startsWith("#EXT-X-STREAM-INF:") || row.startsWith("#EXT-X-MEDIA:") ||
                row.startsWith("#EXT-X-KEY:") || row.startsWith("#EXT-X-BYTERANGE:") ||
                row.startsWith("#EXT-X-MAP:") || row.startsWith("#EXT-X-START:") || row == "#EXT-X-GAP" ||
                row.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") ||
                row.startsWith("#EXT-X-MEDIA-SEQUENCE:") && row.substringAfter(':') != "0" ||
                row.startsWith("#EXT-X-PLAYLIST-TYPE:") && row.substringAfter(':') != "VOD"
        }) return null
        var pending: BigDecimal? = null
        var discontinuity = false
        var total = BigDecimal.ZERO
        val segments = mutableListOf<VodPlaylistSegment>()
        val canonical = StringBuilder()
        for (row in rows) {
            if (row == "#EXT-X-DISCONTINUITY") discontinuity = true
            else if (row.startsWith("#EXTINF:")) {
                if (pending != null) return null
                pending = row.substringAfter(':').substringBefore(',').toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal(120) && it.scale() <= 6 } ?: return null
            } else if (!row.startsWith('#')) {
                val duration = pending ?: return null
                val resolved = uri(base.resolve(row).toString()) ?: return null
                val clean = endpoint(resolved)
                canonical.append(if (discontinuity) '1' else '0').append('|').append(duration.stripTrailingZeros().toPlainString()).append('|').append(clean).append('\n')
                segments += VodPlaylistSegment(total, duration, clean, discontinuity)
                if (segments.size > 10000) return null
                total += duration
                if (total > BigDecimal(86400)) return null
                pending = null; discontinuity = false
            }
        }
        if (pending != null || segments.isEmpty()) return null
        VodPlaylistSnapshot(sha256(canonical.toString()), segments, total)
    }.getOrNull() }
}

/** One instance per player generation. Unknown manifests never authorize a skip. */
class ConfirmedAdPlaylistSession(rootUrl: String, rules: List<ConfirmedAdRule> = ConfirmedVodAds.rules) {
    private val root = ConfirmedVodAds.uri(rootUrl)
    private val candidates = rules.filter { rule -> root != null && root.host.equals(rule.host, true) && root.rawPath in setOf(rule.entryPath, rule.mediaPath) }
    private var approved: ConfirmedAdRule? = candidates.firstOrNull { root?.rawPath == it.mediaPath }
    @Synchronized fun accepts(url: String): Boolean {
        val value = ConfirmedVodAds.uri(url) ?: return false
        return candidates.isNotEmpty() && (root?.let { ConfirmedVodAds.endpoint(value) == ConfirmedVodAds.endpoint(it) } == true ||
            approved?.let { value.host.equals(it.host, true) && value.scheme == root?.scheme && value.port == root?.port && value.rawPath == it.mediaPath } == true)
    }
    @Synchronized fun inspect(url: String, text: String): ConfirmedAdInspection {
        if (!accepts(url)) return ConfirmedAdInspection(false)
        val value = ConfirmedVodAds.uri(url) ?: return ConfirmedAdInspection(false)
        val isEntry = root?.let { ConfirmedVodAds.endpoint(value) == ConfirmedVodAds.endpoint(it) } == true
        if (isEntry && candidates.none { value.rawPath == it.mediaPath }) {
            approved = candidates.firstOrNull { rule ->
                rule.masterSha256 != null && ConfirmedVodAds.masterFingerprint(text) == rule.masterSha256 &&
                    ConfirmedVodAds.lines(text)?.filterNot { it.startsWith('#') }?.singleOrNull()?.let { child ->
                        ConfirmedVodAds.uri(value.resolve(child).toString())?.let { uri -> uri.host.equals(rule.host, true) && uri.scheme == value.scheme && uri.port == value.port && uri.rawPath == rule.mediaPath }
                    } == true
            }
            return ConfirmedAdInspection(true)
        }
        val rule = approved ?: return ConfirmedAdInspection(true)
        val snapshot = ConfirmedVodAds.snapshot(url, text) ?: return ConfirmedAdInspection(true)
        if (snapshot.fingerprint != rule.mediaSha256 || snapshot.segments.size != rule.segmentCount) return ConfirmedAdInspection(true)
        val ranges = rule.blocks.map { block ->
            val first = snapshot.segments.getOrNull(block.first) ?: return ConfirmedAdInspection(true)
            val last = snapshot.segments.getOrNull(block.last) ?: return ConfirmedAdInspection(true)
            if (block.first < 0 || block.last < block.first || !first.discontinuity || snapshot.segments.getOrNull(block.last + 1)?.discontinuity != true) return ConfirmedAdInspection(true)
            val start = (first.start * BigDecimal(1000)).setScale(0, RoundingMode.FLOOR).longValueExact()
            val end = ((last.start + last.duration) * BigDecimal(1000)).setScale(0, RoundingMode.CEILING).longValueExact()
            if (end - start !in 1000L..120000L) return ConfirmedAdInspection(true)
            VodSkipRange(start, end)
        }
        if (ranges.isEmpty() || ranges.zipWithNext().any { (a,b) -> a.endMs > b.startMs }) return ConfirmedAdInspection(true)
        return ConfirmedAdInspection(true, ConfirmedAdMatch(rule.id, ranges))
    }
}
