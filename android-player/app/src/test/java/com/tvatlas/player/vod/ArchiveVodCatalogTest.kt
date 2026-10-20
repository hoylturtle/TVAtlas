package com.tvatlas.player.vod

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ArchiveVodCatalogTest {
    private val client = OkHttpClient()
    @Test fun classicListingUsesReleaseYearAndRequiresPublicDomainLabel() = runBlocking {
        var requestUrl = ""
        val result = ArchiveVodCatalog.load(client, 1, "A \"movie\"", null, "film", false, null, null) { url ->
            requestUrl = url
            Json.parseToJsonElement("""{"response":{"numFound":2,"docs":[{"identifier":"classic","title":"Classic","year":1940,"publicdate":"2026-10-20","licenseurl":"https://creativecommons.org/publicdomain/mark/1.0/"},{"identifier":"unverified","title":"No license","year":1960}]}}""").jsonObject
        }
        assertEquals(listOf("Classic"), result.movies.map { it.name })
        assertEquals("1940", result.movies.single().year)
        assertEquals(0L, result.movies.single().updatedAt)
        assertTrue(requestUrl.contains("year%3A%5B1888%20TO%201969%5D"))
    }
    @Test fun currentYearHomeDoesNotFetchClassicArchiveOrInventNewReleases() = runBlocking {
        val result = ArchiveVodCatalog.load(client, 1, "", null, null, false, "2026", null) { error("Must not contact classic catalogue for current releases") }
        assertTrue(result.movies.isEmpty())
    }
    @Test fun detailsKeepTelevisionIdentityAndEncodeNativeMediaPaths() = runBlocking {
        val result = ArchiveVodCatalog.load(client, 1, "", "classic_tv_episode", null, false, null, null) {
            Json.parseToJsonElement("""{"metadata":{"title":"Classic episode","year":"1958","collection":["classic_tv"],"licenseurl":"http://creativecommons.org/licenses/publicdomain/"},"files":[{"name":"episode one.mp4","size":"200"},{"name":"readme.txt","size":"5"}] }""").jsonObject
        }
        assertEquals("欧美剧", result.movies.single().category)
        assertEquals("https://archive.org/download/classic_tv_episode/episode%20one.mp4", result.movies.single().episodes.single().url)
    }
    @Test fun separateProgrammeFilesAreNotFallbacksForTheSameEpisode() = runBlocking {
        val result = ArchiveVodCatalog.load(client, 1, "", "collection", null, false, null, null) {
            Json.parseToJsonElement("""{"metadata":{"title":"Episodes","collection":["classic_tv"],"licenseurl":"http://creativecommons.org/licenses/publicdomain/"},"files":[{"name":"ep1.mp4"},{"name":"ep1_low.mp4","original":"ep1.mp4"},{"name":"ep2.mp4"}]}""").jsonObject
        }
        assertEquals(setOf("ep1", "ep2"), result.movies.single().episodes.map { it.name }.toSet())
        assertEquals(2, result.movies.single().episodes.count { it.name == "ep1" })
    }
    @Test fun detailsWithoutLicenseCannotSupplyPlayback() = runBlocking {
        try {
            ArchiveVodCatalog.load(client, 1, "", "unverified", null, false, null, null) {
                Json.parseToJsonElement("""{"metadata":{"title":"Unknown"},"files":[{"name":"movie.mp4"}]}""").jsonObject
            }
            fail("Expected explicit licence requirement")
        } catch (_: IllegalArgumentException) { }
    }
}
