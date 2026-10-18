package com.tvatlas.player.vod

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VodQualityCacheTest {
    @Test fun actualResolutionOverridesAdvertisedHeightAndExpiredOrFailedEntriesAreIgnored() = runBlocking {
        val dir = Files.createTempDirectory("quality").toFile(); var now = 1_000_000L
        val url = "https://fixture.test/video?token=secret-value"
        try {
            val cache = VodQualityCache(dir.resolve("cache.json")) { now }
            cache.record(url, "direct", 2160, 30)
            cache.record(url, "direct", 720, 20, actual = true)
            cache.record(url, "direct", 2160, 50)
            assertEquals(720, cache.get(url, "direct")!!.height)
            assertNull(cache.get(url, "other-network"))
            val restored = VodQualityCache(dir.resolve("cache.json")) { now }
            assertEquals(720, restored.get(url, "direct")!!.height)
            assertFalse(dir.resolve("cache.json").readText().contains("secret-value"))
            now += 300_001; assertNull(cache.get(url, "direct"))
            cache.record(url, "direct", 1080, 10); cache.invalidate(url, "direct")
            assertNull(cache.get(url, "direct"))
            cache.reject(url, "direct"); assertFalse(cache.get(url, "direct")!!.playable)
            now += 300_001; assertNull(cache.get(url, "direct"))
        } finally { dir.deleteRecursively() }
    }
}
