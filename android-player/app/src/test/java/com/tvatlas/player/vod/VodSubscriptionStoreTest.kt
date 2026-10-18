package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VodSubscriptionStoreTest {
    @Test fun unifiedFeedsShareOwnershipAndManualDeletionSurvivesRefreshAndRestart() = runBlocking {
        val dir = Files.createTempDirectory("unified-feeds").toFile()
        val shared = "https://live.test/shared.m3u"
        val payload = """{"sources":[{"name":"影视","url":"https://vod.test/api"}],"liveSources":[{"name":"共享直播","url":"$shared"}]}"""
        val owned = mutableSetOf<String>()
        val apply: suspend (List<SubscribedLiveSource>, Set<String>) -> Unit = { desired, removed -> owned.addAll(desired.map { it.url }); owned.removeAll(removed) }
        try {
            val store = VodSubscriptionStore(dir, emptyList(), this, emptyMap(), { payload }, { error("Explicit JSON should be tested on use, not scanned on import") }, apply)
            store.addFeed("A", "https://config.test/a"); store.addFeed("B", "https://config.test/b")
            assertEquals(setOf(shared), owned); assertEquals(1, store.state.value.sources.size)
            store.removeFeed("https://config.test/a"); assertEquals(setOf(shared), owned)
            store.ignoreLive(shared); owned.remove(shared) // The UI deletes the managed Room playlist after recording this choice.
            store.sync(true).join(); assertTrue(owned.isEmpty())
            val restored = VodSubscriptionStore(dir, emptyList(), this, emptyMap(), { payload }, { true }, apply)
            restored.sync(true).join(); assertTrue(owned.isEmpty())
            assertEquals(setOf("https://config.test/b"), restored.state.value.subscriptions.keys)
            restored.removeFeed("https://config.test/b"); assertTrue(restored.state.value.sources.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedNewLiveListPreservesOldFeedAndRemovedDefaultDoesNotReturn() = runBlocking {
        val dir = Files.createTempDirectory("unified-failure").toFile()
        val address = "https://config.test/default"
        var body = """{"liveSources":[{"name":"可用","url":"https://live.test/old.m3u"}]}"""
        try {
            val store = VodSubscriptionStore(dir, emptyList(), this, mapOf("默认" to address), { body }, { true },
                applyLive = { desired, _ -> require(desired.none { it.url.contains("bad") }) })
            store.sync(true).join(); assertEquals(1, store.state.value.liveCount)
            body = """{"liveSources":[{"name":"故障","url":"https://live.test/bad.m3u"}]}"""
            store.sync(true).join(); assertEquals(1, store.state.value.liveCount)
            assertTrue(store.state.value.message.contains("上次记录"))
            store.removeFeed(address)
            val restored = VodSubscriptionStore(dir, emptyList(), this, mapOf("默认" to address), { error("Deleted default must not be fetched") }, { true })
            restored.sync(true).join(); assertTrue(restored.state.value.subscriptions.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun replacesEndpointPreservesFailedRefreshAndNeverExecutesSpider() = runBlocking {
        val dir = Files.createTempDirectory("vod-subscriptions").toFile()
        var text = """{"sites":[{"name":"Native","type":1,"api":"https://api.test/old"}]}"""
        var valid = true
        val checked = mutableListOf<String>()
        val store = VodSubscriptionStore(dir, emptyList(), this, mapOf("fixture" to "https://config.test/root"),
            fetch = { text }, validate = { checked += it; valid })
        suspend fun sync() { withTimeout(3000) { store.sync(true).join() } }
        try {
            sync(); assertEquals("https://api.test/old", store.state.value.sources.single().api)
            text = """{"sites":[{"name":"Native","type":1,"api":"https://api.test/new"}]}"""
            sync()
            text = """{"sites":[{"name":"Native","type":3,"api":"csp_Guard"}]}"""
            val before = checked.size; sync()
            assertEquals(before, checked.size)
            assertTrue(store.state.value.sources.single().api.endsWith("new"))
            text = """{"sites":[{"name":"Native","type":1,"api":"https://api.test/fail"}]}"""; valid = false
            sync(); assertTrue(store.state.value.sources.single().api.endsWith("new"))
        } finally { dir.deleteRecursively() }
    }
}
