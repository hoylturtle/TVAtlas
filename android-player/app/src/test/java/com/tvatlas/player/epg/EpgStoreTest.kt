package com.tvatlas.player.epg

import com.tvatlas.core.model.Channel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EpgStoreTest {
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
