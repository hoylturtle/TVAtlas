package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class UnifiedVodTest {
    @Test fun fullMovieLabelsMatchLegacyGenreKeysAndShortDramasAreNotSeriesOrUnknown() {
        val film = CatalogMovie("1", "fixture", "战争片", emptyList())
        assertTrue(UnifiedVod.movieGroups.contains("战争片"))
        assertTrue(UnifiedVod.matches(film, VodFilter(genre = "战争片")))
        assertTrue(UnifiedVod.matches(film, VodFilter(genre = "战争")))
        assertFalse(UnifiedVod.matches(film, VodFilter(genre = "爱情片")))
        assertEquals(listOf("9"), UnifiedVod.categoryIds(listOf(CatalogType("9", "战争片")), VodKind.MOVIE, "战争片"))
        val drama = CatalogMovie("2", "fixture", "现代都市短剧", emptyList())
        assertTrue(UnifiedVod.matches(drama, VodFilter(VodKind.SHORT_DRAMA)))
        assertFalse(UnifiedVod.matches(drama, VodFilter(VodKind.SERIES)))
        assertEquals(VodKind.OTHER, UnifiedVod.kind("未分类"))
        assertEquals(VodKind.SHORT_DRAMA, UnifiedVod.kind("反转爽剧"))
        assertFalse(UnifiedVod.browseKinds.contains(VodKind.OTHER))
    }
    @Test fun recentOrderUsesProviderUpdateTimeRatherThanReleaseYear() {
        val page = VodCatalog.parse("""{"list":[{"vod_id":"old","vod_name":"旧片新上架","type_name":"动作片","vod_year":"1999","vod_time":"2026-01-02 03:04:05"},{"vod_id":"new","vod_name":"新片旧记录","type_name":"动作片","vod_year":"2026","vod_time":"2025-12-01 00:00:00"},{"vod_id":"unknown","vod_name":"未知","type_name":"动作片","vod_time":"invalid"}]}""")
        val items = UnifiedVod.merge(page.movies.map { VodOffer("source", it) })
        assertEquals(listOf("旧片新上架", "新片旧记录", "未知"), UnifiedVod.sorted(items.reversed(), VodSort.DEFAULT).map { it.movie.name })
        assertEquals(listOf("新片旧记录", "旧片新上架", "未知"), UnifiedVod.sorted(items, VodFilter().sort).map { it.movie.name })
        assertFalse(UnifiedVod.matches(page.movies.first(), VodFilter(year = "2026")))
        assertTrue(UnifiedVod.matches(page.movies[1], VodFilter(year = "2026")))
        assertEquals(0L, page.movies.last().updatedAt)
    }
    private fun movie(name: String = "同名作品", year: String = "2026", area: String = "大陆", category: String = "动作片") =
        CatalogMovie("id", name, category, emptyList(), year = year, area = area, genres = "动作,悬疑")
    @Test fun mergesEquivalentOffersButKeepsRemakesSeasonsAndUnknownYearsSeparate() {
        val original = movie()
        val offers = listOf(VodOffer("a", original), VodOffer("b", original.copy(id = "other", area = "中国大陆")),
            VodOffer("c", movie(year = "1998")), VodOffer("d", movie(year = "")), VodOffer("e", movie(name = "同名作品第2季")))
        val merged = UnifiedVod.merge(offers)
        assertEquals(4, merged.size)
        assertEquals(2, merged.first().offers.size)
        assertEquals("香港", UnifiedVod.region("中国香港"))
        assertEquals(2, UnifiedVod.merge(listOf(VodOffer("x", movie(area = "印度")), VodOffer("y", movie(area = "俄罗斯")))).size)
    }
    @Test fun combinesGenreRegionAndYearFiltersWithoutGuessingMissingMetadata() {
        val film = movie()
        assertTrue(UnifiedVod.matches(film, VodFilter(VodKind.MOVIE, "动作", "大陆", "2026")))
        assertFalse(UnifiedVod.matches(film, VodFilter(VodKind.MOVIE, "喜剧", "大陆", "2026")))
        assertFalse(UnifiedVod.matches(movie(year = ""), VodFilter(year = "2026")))
        assertTrue(UnifiedVod.matches(movie(year = ""), VodFilter(year = "未知")))
        assertTrue(UnifiedVod.matches(movie(category = "大陆综艺").copy(genres = "真人秀"), VodFilter(VodKind.VARIETY, "真人秀", "大陆", "2026")))
        assertEquals(VodKind.ANIMATION, UnifiedVod.kind("日韩动漫"))
    }
    @Test fun choosesNativeCategoryIdsAndQualityHints() {
        val types = listOf(CatalogType("1", "电影"), CatalogType("6", "动作片"), CatalogType("7", "喜剧片"), CatalogType("13", "国产剧"), CatalogType("25", "日本剧"))
        assertEquals(listOf("6"), UnifiedVod.categoryIds(types, VodKind.MOVIE, "动作"))
        assertEquals(listOf("25"), UnifiedVod.categoryIds(types, VodKind.SERIES, "日本"))
        assertEquals(2160, VodQuality.height("4K"))
        assertEquals(1080, VodQuality.height("1080P"))
        assertEquals(0, VodQuality.height("未知"))
    }
    @Test fun logicalEpisodeResumeIdentitySurvivesProviderChanges() {
        val a = VodItem.episode("https://a.test/1.mp4", "第1集", "work:episode:1")
        val b = VodItem.episode("https://b.test/1.m3u8", "第1集", "work:episode:1")
        val other = VodItem.episode("https://a.test/2.mp4", "第2集", "work:episode:2")
        assertEquals(a.key, b.key)
        assertNotEquals(a.key, other.key)
    }
}
