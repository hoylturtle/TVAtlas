package com.tvatlas.player.epg

import com.tvatlas.core.model.Channel
import com.tvatlas.core.epg.Guide
import com.tvatlas.core.epg.Programme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EpgStoreTest {
    @Test fun expiredCacheIsLoadedWithoutFetchingUntilExplicitRefresh() = runBlocking {
        val file = File.createTempFile("epg-expired", ".json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val url = "https://fixture.test/epg"
        val now = System.currentTimeMillis()
        file.writeText(Json.encodeToString(CachedGuide(url, now - 7 * 3600000L,
            Guide(mapOf("Other" to listOf("Other")), listOf(Programme("Other", now, now + 3600000, "cached"))))))
        var requests = 0
        try {
            val store = EpgStore(file, scope) { requests++; xml("updated") }
            store.load(url)
            assertTrue(store.needsRefresh())
            assertEquals(0, requests)
            assertEquals("cached", store.state.value.guide.programmes.single().title)
            store.refresh(url)
            assertEquals(1, requests)
            assertFalse(store.needsRefresh())
            assertEquals("updated", store.state.value.guide.programmes.single().title)
        } finally { scope.cancel(); file.delete() }
    }

    @Test fun loadingLargeLegacyCacheRemovesUnrelatedProgrammesWithoutNetwork() = runBlocking {
        val file = File.createTempFile("epg-large", ".json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = System.currentTimeMillis()
        val channel = Channel("hebei", "河北卫视", "中国大陆 · 卫视", tvgId = "Hebei", streams = emptyList())
        val programmes = listOf(Programme("Hebei", now - 1000, now + 600000, "当前节目")) +
            (0 until 15000).map { Programme("Unrelated", now + it * 1000, now + it * 1000 + 1000, "无关节目$it") }
        file.writeText(Json.encodeToString(CachedGuide("https://fixture.test/epg", now,
            Guide(mapOf("Hebei" to listOf("河北卫视"), "Unrelated" to listOf("其他")), programmes))))
        try {
            val store = EpgStore(file, scope, channels = { listOf(channel) }) { error("fresh cache must not fetch") }
            store.load("https://fixture.test/epg")
            assertEquals(1, store.state.value.channelCount)
            assertEquals(listOf("当前节目"), store.state.value.guide.programmes.map { it.title })
            assertTrue(file.length() < 5000)
            assertFalse(store.needsRefresh())
            store.invalidateLibrary()
            assertTrue(store.needsRefresh())
            assertEquals(1, store.state.value.guide.programmes.size)
        } finally { scope.cancel(); file.delete() }
    }

    private fun xml(title: String) = """<tv><channel id="Other"><display-name>Other</display-name></channel><programme channel="Other" start="20990101000000 +0000" stop="20990101010000 +0000"><title>$title</title></programme></tv>""".toByteArray()
    @Test fun officialSupplementSurvivesXmlRefreshAndDiskReload() = runBlocking {
        val file = File.createTempFile("epg", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Hong_Kong") }.format(Date())
        val official = """[{"item":[{"epg":[{"start_datetime":"$now","programme_title_tc":"Official live"}]}]}]""".toByteArray()
        val channel = Channel("jade", "翡翠台", "香港", tvgId = "Jade.hk", streams = emptyList())
        try {
            val store = EpgStore(file, scope) { if (it.endsWith("official")) official else xml("XML") }
            store.refresh("https://example.test/xml")
            assertTrue(store.official(channel, "https://example.test/official", true))
            store.refresh("https://example.test/xml")
            assertEquals("Official live", store.state.value.entries(channel).single().title)
            val restored = EpgStore(file, scope) { xml("unused") }
            restored.load("https://example.test/xml")
            assertTrue(restored.hasOfficial(channel))
            assertEquals("Official live", restored.state.value.entries(channel).single().title)
        } finally { scope.cancel(); file.delete() }
    }
    @Test fun changingSourceWhileFetchIsRunningQueuesNewestAndRejectsOldResult() = runBlocking {
        val file = File.createTempFile("epg", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        try {
            val store = EpgStore(file, scope) { url -> if (url.endsWith("A")) { started.complete(Unit); finish.await(); xml("A") } else xml("B") }
            val first = launch { store.refresh("https://example.test/A") }
            started.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) { store.refresh("https://example.test/B") }
            finish.complete(Unit); first.join(); second.join()
            assertEquals("https://example.test/B", store.state.value.source)
            assertEquals(listOf("B"), store.state.value.guide.programmes.map { it.title })
            assertFalse(store.state.value.loading)
        } finally { scope.cancel(); file.delete() }
    }
    @Test fun cancellationClearsLoadingAndFailedRefreshKeepsData() = runBlocking {
        val file = File.createTempFile("epg", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        try {
            val store = EpgStore(file, scope) { url -> when {
                url.endsWith("slow") -> { started.complete(Unit); awaitCancellation() }
                url.endsWith("bad") -> throw java.io.IOException("fixture")
                else -> xml("cached")
            } }
            store.refresh("https://example.test/good")
            val slow = launch { store.refresh("https://example.test/slow") }; started.await(); slow.cancelAndJoin()
            assertFalse(store.state.value.loading)
            store.refresh("https://example.test/bad")
            assertEquals("cached", store.state.value.guide.programmes.single().title)
            assertNotNull(store.state.value.error)
        } finally { scope.cancel(); file.delete() }
    }
}
