package com.tvatlas.core.epg

import kotlinx.serialization.Serializable
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

@Serializable data class Programme(val channel: String, val start: Long, val end: Long, val title: String, val imageUrl: String? = null)
@Serializable data class Guide(val names: Map<String, List<String>> = emptyMap(), val programmes: List<Programme> = emptyList()) {
    fun channelId(tvgId: String?, name: String): String? = tvgId?.takeIf { id -> programmes.any { it.channel == id } }
        ?: names.entries.firstOrNull { (_, labels) -> labels.any { normalize(it) == normalize(name) } }?.key
    companion object { fun normalize(value: String): String {
        val clean = value.lowercase(Locale.ROOT).replace("無綫", "无线").replace("無線", "无线").replace("新聞", "新闻").replace(Regex("[\\s·._-]"), "")
        return Regex("^cctv[0-9]+\\+?").find(clean)?.value ?: clean
    } }
}
object XmlTv {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val timePattern = Regex("""^(\d{14})(?:\s+([+-]\d{4}|Z))?$""", RegexOption.IGNORE_CASE)
    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).apply { isLenient = false }
    }
    fun time(value: String?): Long? {
        if (value == null) return null
        val parts = timePattern.matchEntire(value.trim()) ?: return null
        return runCatching { requireNotNull(timeFormatter.get()).parse(parts.groupValues[1] + " " + parts.groupValues[2].takeUnless { it.isBlank() || it == "Z" }.orEmpty().ifBlank { "+0000" })?.time }.getOrNull()
    }
    fun parse(bytes: ByteArray): Guide {
        require(bytes.size <= MAX_BYTES) { "节目单超过 16 MiB" }
        require(!bytes.toString(Charsets.UTF_8).replace("\u0000", "").contains("<!DOCTYPE", true)) { "节目单不允许外部实体" }
        val names = linkedMapOf<String, MutableList<String>>()
        val programmes = mutableListOf<Programme>()
        var channel: String? = null; var programmeChannel: String? = null
        var start: Long? = null; var end: Long? = null; var capture: String? = null
        val text = StringBuilder(); var title = ""; var imageUrl: String? = null
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "channel" -> { channel = attributes.getValue("id"); require(names.size < 20000) }
                    "programme" -> { programmeChannel = attributes.getValue("channel"); start = time(attributes.getValue("start")); end = time(attributes.getValue("stop")); title = ""; imageUrl = null }
                    "icon" -> if (programmeChannel != null && imageUrl == null) {
                        imageUrl = attributes.getValue("src")?.takeIf { it.length <= 8192 && com.tvatlas.core.model.httpUri(it) != null }
                    }
                    "display-name", "title" -> { capture = qName; text.setLength(0) }
                }
            }
            override fun characters(ch: CharArray, offset: Int, length: Int) { if (capture != null && text.length < 1000) text.append(ch, offset, minOf(length, 1000 - text.length)) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "display-name" -> { channel?.let { names.getOrPut(it) { mutableListOf() }.add(text.toString().trim()) }; capture = null }
                    "title" -> { if (title.isEmpty()) title = text.toString().trim(); capture = null }
                    "channel" -> channel = null
                    "programme" -> {
                        val id = programmeChannel; val at = start
                        if (!id.isNullOrBlank() && at != null && title.isNotBlank() && (end == null || end!! > at)) {
                            require(programmes.size < 200000) { "节目数量过多" }
                            programmes.add(Programme(id, at, end ?: 0, title, imageUrl))
                        }
                        programmeChannel = null
                    }
                }
            }
        }
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }.newSAXParser().xmlReader
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities").forEach { runCatching { reader.setFeature(it, false) } }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External entities disabled") }
        reader.contentHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
        val complete = programmes.groupBy { it.channel }.values.flatMap { items ->
            val ordered = items.distinct().sortedBy { it.start }
            ordered.mapIndexed { i, item -> if (item.end > item.start) item else item.copy(end = ordered.getOrNull(i + 1)?.start ?: item.start + 2 * 60 * 60 * 1000) }
        }
        require(complete.isNotEmpty()) { "节目单没有有效节目" }
        return Guide(names.mapValues { it.value.toList() }, complete)
    }
}
