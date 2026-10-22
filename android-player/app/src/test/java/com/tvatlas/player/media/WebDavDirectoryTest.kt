package com.tvatlas.player.media

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class WebDavDirectoryTest {
    private fun response(href: String, name: String, directory: Boolean = false) = """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:displayname>$name</d:displayname><d:resourcetype>${if (directory) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>4096</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
    private fun xml(vararg responses: String) = """<d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>"""
    @Test fun listingKeepsOnlyImmediateVideoFilesAndDirectoriesInsideSelectedFolder() {
        val text = xml(response("/dav/Movies/", "root", true), response("/dav/Movies/a%20b.mkv", "影片.mkv"),
            response("/dav/Movies/Series/", "剧集", true), response("/dav/Movies/poster.jpg", "poster.jpg"),
            response("https://other.invalid/dav/Movies/theft.mp4", "theft.mp4"), response("/dav/outside.mp4", "outside.mp4"),
            response("/dav/Movies/Series/episode.mp4", "episode.mp4"), response("/dav/Movies/%2e%2e/private.mp4", "private.mp4"))
        val found = WebDavDirectory.parse("https://nas.test/dav/Movies/", "https://nas.test/dav/Movies/", text)
        assertEquals(listOf("影片.mkv", "剧集"), found.map { it.name })
        assertEquals(4096L, found.first().size)
    }
    @Test fun refusesExternalEntitiesAndEncodedPathEscapes() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavDirectory.parse("https://nas.test/m/", "https://nas.test/m/", "<!DOCTYPE d [<!ENTITY x SYSTEM 'file:///secret'>]><d/>")
        }
        for (url in listOf("https://nas.test/media2/movie.mp4", "https://nas.test/media/%2e%2e/private.mp4", "https://nas.test/media/x%2f../private.mp4", "http://nas.test/media/a.mp4", "https://user:pass@nas.test/media/a.mp4"))
            assertFalse(url, WebDavDirectory.inScope("https://nas.test/media/", url))
    }
    @Test fun authenticatedPropfindIsReadOnlyAndDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val root = server.url("/Movies/").toString()
            val folder = MediaFolder("id", "NAS", root, true, "user", "private-password")
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml(response("/Movies/a.mp4", "a.mp4"))))
            assertEquals("a.mp4", WebDavDirectory.list(folder, root).single().name)
            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method); assertEquals("1", request.getHeader("Depth"))
            assertNotNull(request.getHeader("Authorization")); assertFalse(folder.toString().contains("private-password"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/Movies/")))
            var failed = false
            try { WebDavDirectory.list(folder, root) } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed); assertEquals(0, other.requestCount)
        } }
    }
}
