package com.tvatlas.player.media

import com.tvatlas.player.network.readCancellable
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.StringReader
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

object WebDavDirectory {
    private val base by lazy { OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build() }
    /** NAS connections are direct and scoped to the configured origin and folder, including media reads. */
    fun client(folder: MediaFolder): OkHttpClient = base.newBuilder().addInterceptor { chain ->
        if (!inScope(folder.uri, chain.request().url.toString())) throw java.io.IOException("Outside configured folder")
        val request = chain.request().newBuilder().apply {
            if (folder.username.isNotEmpty() || folder.password.isNotEmpty()) header("Authorization", Credentials.basic(folder.username, folder.password, Charsets.UTF_8))
        }.build()
        chain.proceed(request)
    }.build()
    suspend fun list(folder: MediaFolder, directory: String): List<MediaEntry> {
        require(inScope(folder.uri, directory))
        val xml = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        val request = Request.Builder().url(directory).header("Depth", "1").method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        return client(folder).newCall(request).readCancellable { response ->
            require(response.code in listOf(200, 207)) { "WebDAV request failed" }
            val bytes = requireNotNull(response.body).byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 2 * 1024 * 1024); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            parse(folder.uri, directory, bytes.toString(Charsets.UTF_8))
        }
    }
    fun inScope(root: String, candidate: String): Boolean {
        val a = root.toHttpUrlOrNull() ?: return false; val b = candidate.toHttpUrlOrNull() ?: return false
        if (a.scheme != b.scheme || a.host != b.host || a.port != b.port || b.username.isNotEmpty() || b.password.isNotEmpty()) return false
        val prefix = a.pathSegments.filter { it.isNotEmpty() }; val parts = b.pathSegments.filter { it.isNotEmpty() }
        if ((prefix + parts).any { it == "." || it == ".." || '/' in it || '\\' in it }) return false
        return parts.take(prefix.size) == prefix
    }
    fun parse(root: String, directory: String, xml: String): List<MediaEntry> {
        require(xml.length <= 2 * 1024 * 1024 && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml))
        require(inScope(root, directory))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
        val doc = builder.parse(InputSource(StringReader(xml)))
        require(doc.documentElement.namespaceURI == "DAV:" && doc.documentElement.localName == "multistatus") { "Not a WebDAV directory" }
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val baseUrl = requireNotNull(directory.toHttpUrlOrNull())
        val currentPath = baseUrl.pathSegments.filter { it.isNotEmpty() }
        return buildList {
            for (i in 0 until minOf(responses.length, 2000)) {
                val element = responses.item(i) as? org.w3c.dom.Element ?: continue
                fun text(tag: String) = element.getElementsByTagNameNS("DAV:", tag).item(0)?.textContent.orEmpty().trim()
                val href = text("href"); if (href.isBlank()) continue
                val url = baseUrl.resolve(href) ?: continue
                if (!inScope(root, url.toString())) continue
                val parts = url.pathSegments.filter { it.isNotEmpty() }
                if (parts.size != currentPath.size + 1 || parts.take(currentPath.size) != currentPath) continue
                val statuses = element.getElementsByTagNameNS("DAV:", "status")
                if (statuses.length > 0 && (0 until statuses.length).none { Regex("\\s200(?:\\s|$)").containsMatchIn(statuses.item(it).textContent) }) continue
                val directoryEntry = element.getElementsByTagNameNS("DAV:", "collection").length > 0
                val name = text("displayname").ifBlank { parts.last() }.take(200)
                if (!directoryEntry && !MediaFileNames.video(name)) continue
                add(MediaEntry(name, url.toString(), directoryEntry, text("getcontentlength").toLongOrNull()?.coerceAtLeast(0) ?: 0))
            }
        }.distinctBy { it.uri }
    }
}
