package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class VodPlaybackSafetyTest {
    @Test fun localDocumentsCannotEnterTheRemoteUrlPath() {
        val item = VodItem.localDocument("content://documents/tree/USB/document/movie", "影片")
        assertEquals(64, item.key.length)
        assertThrows(IllegalArgumentException::class.java) { VodItem.direct(item.url) }
        assertThrows(IllegalArgumentException::class.java) { VodItem.localDocument("https://example.test/movie.mp4", "影片") }
    }
    @Test fun skipsRejectInvalidBoundsAndMergeOverlappingMarks() {
        for ((a,b) in listOf(-1L to 2000L, 1000L to 1500L, 4000L to 2000L, 1000L to 11000L, Long.MAX_VALUE to Long.MIN_VALUE))
            assertThrows(IllegalArgumentException::class.java) { VodPlaybackSafety.validateSkip(a,b,10000) }
        val ranges = VodPlaybackSafety.merge(listOf(VodSkipRange(3000,6000), VodSkipRange(1000,4000), VodSkipRange(8000,9000)))
        assertEquals(listOf(VodSkipRange(1000,6000), VodSkipRange(8000,9000)), ranges)
    }
    @Test fun changingCutRequiresResumeConfirmationButSmallContainerDifferencesDoNot() {
        assertTrue(VodPlaybackSafety.needsResumeConfirmation(7200000,5400000))
        assertFalse(VodPlaybackSafety.needsResumeConfirmation(7200000,7190000))
        assertFalse(VodPlaybackSafety.needsResumeConfirmation(-1,5400000))
    }
    @Test fun sameTitleDoesNotMergeDifferentYearsOrContentKinds() {
        val a = CatalogMovie("a", "同名作品", "剧情片", emptyList(), year="2026", area="中国")
        val b = a.copy(id="b", category="国产剧")
        val c = a.copy(id="c", year="2019")
        assertEquals(3, UnifiedVod.merge(listOf(VodOffer("one",a),VodOffer("two",b),VodOffer("three",c))).size)
    }
}
