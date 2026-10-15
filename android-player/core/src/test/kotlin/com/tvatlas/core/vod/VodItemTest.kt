package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class VodItemTest {
    @Test fun videoUrlsStayOutOfObjectDescriptionsAndHaveStableOpaqueKeys() {
        val item = VodItem.direct("https://fixture.test/movie.mp4?token=private-fixture", "电影")
        assertFalse(item.toString().contains("private-fixture"))
        assertFalse(item.toString().contains("movie.mp4"))
        assertEquals(64, item.key.length)
        assertEquals(item.key, VodItem.direct(item.url).key)
        assertNotEquals(item.key, VodItem.direct("https://fixture.test/another.mp4").key)
    }
    @Test fun rejectShareWebPagesAndUnsafeSchemesWithoutRejectingSignedMediaUrls() {
        for (url in listOf("file:///movie.mp4", "javascript:alert(1)", "https://u:p@fixture.test/a", "https://pan.quark.cn/s/example", "https://115cdn.com/s/example")) {
            assertThrows(IllegalArgumentException::class.java) { VodItem.direct(url) }
        }
        assertEquals("视频点播", VodItem.direct("http://192.168.1.10/video?key=fixture").title)
    }
    @Test fun resumeClampsDurationAndStartsCompletedMoviesOver() {
        assertEquals(60000, VodResume.position(VodBookmark(60000, 100000), 100000))
        assertEquals(0, VodResume.position(VodBookmark(99000, 100000), 100000))
        assertEquals(0, VodResume.position(VodBookmark(Long.MAX_VALUE, 100000), 100000))
        assertEquals(0, VodResume.position(VodBookmark(3000, 100000), 100000))
        assertEquals(5000, VodResume.position(VodBookmark(5000, 12000), 12000))
        assertEquals(0, VodResume.position(VodBookmark(60000, 100000), -1))
    }
}
