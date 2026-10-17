package com.tvatlas.player.vod

import com.tvatlas.core.vod.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VodSubscriptionStoreTest {
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
