package com.tvatlas.core.vod

import com.tvatlas.core.vod.stremio.*
import org.junit.Assert.*
import org.junit.Test

class StremioProtocolTest {
    @Test fun supportsResourceSpecificTypesAndIdPrefixes() {
        val m = StremioProtocol.manifest("""{"id":"test","name":"Test","types":["movie","series"],"resources":["catalog",{"name":"stream","types":["movie"],"idPrefixes":["tt"]}],"catalogs":[{"id":"search","type":"movie","extra":[{"name":"search","isRequired":true},{"name":"skip"}]}]}""")
        assertTrue(m.supports("stream", "movie", "tt1234567")); assertFalse(m.supports("stream", "series", "tt1234567:1:1"))
        assertFalse(m.supports("stream", "movie", "custom:1")); assertEquals(listOf("search"), m.catalogs.single().required)
        assertTrue(m.catalogs.single().search); assertTrue(m.catalogs.single().skip)
    }
    @Test fun separatesSeasonsAndOnlyAdvancesWithinSameSeason() {
        val a = CatalogEpisode("S01E01", "https://a/1.mp4", "a")
        val b = a.copy(name = "S02E01", url = "https://a/2.mp4")
        val c = a.copy(name = "S01E02", url = "https://a/3.mp4")
        val choices = VodEpisodeChoices.of(CatalogMovie("1", "Show", "剧集", listOf(a,b,c)))
        assertEquals(3, choices.size)
        assertEquals("S01E02", VodEpisodeChoices.next(choices, "S01E01")?.name)
        assertNull(VodEpisodeChoices.next(choices, "S01E02"))
        assertNotEquals(VodEpisodeChoices.identity("S01E01"), VodEpisodeChoices.identity("第1集"))
    }
    @Test fun catalogIsMetadataAndImdbIdentityDoesNotMixMovieAndSeries() {
        val data = """{"metas":[{"id":"tt1234567","type":"movie","name":"Name","releaseInfo":"2025–2026","country":"USA","genres":["Action"]}]}"""
        val movie = StremioProtocol.catalog(data, "movie", "provider").single()
        assertTrue(movie.discoveryOnly); assertTrue(movie.episodes.isEmpty()); assertEquals("2025", movie.year)
        assertEquals("美国", movie.area); assertTrue(UnifiedVod.matches(movie, VodFilter(genre="动作片")))
        assertEquals(UnifiedVod.identity(movie), UnifiedVod.identity(movie.copy(name = "另一个语言标题")))
        assertNotEquals(UnifiedVod.identity(movie), UnifiedVod.identity(movie.copy(category="剧集")))
        val custom = movie.copy(imdbId="", id="movie:42")
        assertNotEquals(UnifiedVod.identity(custom), UnifiedVod.identity(custom.copy(identityNamespace="another")))
    }
    @Test fun detailDefersStreamLookupAndIgnoresMalformedEpisodes() {
        val movie = StremioProtocol.detail("""{"meta":{"id":"tt1234567","type":"series","name":"Show","videos":[{"id":"tt1234567:1:1","season":1,"episode":1},{"id":"bad","episode":2}]}}""", "provider", "series")
        assertEquals("S01E01", movie.episodes.single().name)
        assertEquals("series:tt1234567:1:1", movie.episodes.single().streamId)
        assertTrue(movie.episodes.single().url.startsWith("stremio://"))
    }
    @Test fun streamsKeepOnlyHttpWithScopedSupportedHeaders() {
        val data = """{"streams":[{"url":"https://a/movie","name":"1080p","behaviorHints":{"proxyHeaders":{"request":{"Authorization":"Bearer secret"}}}},{"infoHash":"123"},{"externalUrl":"https://example.org"},{"url":"https://a/b.mp4","behaviorHints":{"proxyHeaders":{"request":{"Host":"bad"}}}},{"url":"content://local/file"}]}"""
        val result = StremioProtocol.streams(data, "正片")
        assertEquals(4, result.unsupported); assertEquals(1, result.episodes.size)
        assertEquals(1080, result.episodes.single().qualityHeight)
        assertEquals("Bearer secret", result.episodes.single().headers["Authorization"])
    }
    @Test fun nullableUnusedSourceFieldsDoNotRejectValidHttpMedia() {
        val result = StremioProtocol.streams("""{"streams":[{"url":"https://media.example/movie.mp4","infoHash":null,"ytId":null,"externalUrl":null}]}""", "正片")
        assertEquals(1, result.episodes.size); assertEquals(0, result.unsupported)
    }
    @Test fun subtitleOnlyAddonIsNotPresentedAsAWorkingFilmSource() {
        try { StremioProtocol.manifest("""{"id":"subs","name":"Subtitles","resources":["subtitles"],"types":["movie"],"catalogs":[]}"""); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("字幕")) }
    }
    @Test fun malformedResponsesDoNotEchoTokens() {
        try { StremioProtocol.catalog("private-token<html>", "movie"); fail() }
        catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("private-token")) }
    }
    @Test fun curatedAddonUsesSameProtocolAndKeepsAttribution() {
        val rows = StremioProtocol.catalog(OpenFilmsAddon.response("catalog", "movie", "open-films"), "movie")
        assertEquals(2, rows.size)
        val detail = StremioProtocol.detail(OpenFilmsAddon.response("meta", "movie", "sintel"), "builtin", "movie")
        assertTrue(detail.summary.contains("Blender Foundation")); assertTrue(detail.summary.contains("creativecommons.org"))
        assertTrue(StremioProtocol.streams(OpenFilmsAddon.response("stream", "movie", "sintel"), "正片").episodes.single().url.contains("archive.org/download/"))
    }
}
