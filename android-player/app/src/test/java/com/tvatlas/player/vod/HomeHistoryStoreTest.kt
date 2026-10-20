package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HomeHistoryStoreTest {
    @Test fun historySurvivesRestartWithoutPersistingMediaAddressesAndKeepsProviderIds() = runBlocking {
        val directory = Files.createTempDirectory("home-history").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = java.io.File(directory, "recent.json")
            val movie = CatalogMovie("provider-id", "已观看电影", "动作片", listOf(CatalogEpisode("正片", "https://example.invalid/private-media?token=secret", "line")))
            val unified = UnifiedVod.merge(listOf(VodOffer("https://example.invalid/catalog", movie))).single()
            val store = HomeHistoryStore(file, scope)
            store.record(unified, "正片").join()
            assertFalse(file.readText().contains("private-media"))
            assertFalse(file.readText().contains("token=secret"))
            val restored = withTimeout(3000) { HomeHistoryStore(file, scope).state.first { it.isNotEmpty() } }.single()
            assertEquals(unified.id, restored.unified().id)
            assertEquals("provider-id", restored.unified().offers.single().movie.id)
            assertTrue(restored.movie.episodes.isEmpty())
            assertEquals("正片", restored.episode)
            store.record(unified, "重看").join()
            assertEquals(1, store.state.value.size)
            assertEquals("重看", store.state.value.single().episode)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
}
