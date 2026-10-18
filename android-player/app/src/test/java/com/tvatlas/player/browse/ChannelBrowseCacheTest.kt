package com.tvatlas.player.browse

import com.tvatlas.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelBrowseCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private fun channels() = listOf(
        Channel("h", "翡翠台", "香港", streams = listOf(Stream("s", "https://video.test/live?token=secret", "p"))),
        Channel("j", "NHK", "日本", streams = emptyList()),
    )
    @Test fun unchangedInputReusesIndexButChangedInputCannotReadStaleChannels() = runBlocking {
        val store = ChannelBrowseCache(folder.root.resolve("memory.json"))
        val input = channels()
        assertNull(store.peek(input))
        val first = store.get(input)
        assertSame(first.index, store.peek(input.toList()))
        assertSame(first.index, store.get(input.toList()).index)
        val changed = input.map { it.copy(name = it.name + "新") }
        assertNull(store.peek(changed))
        assertNotSame(first.index, store.get(changed).index)
        assertNull(store.peek(input))
    }
    @Test fun coldRestartReusesEveryClassificationAndDoesNotStorePlaybackUrls() = runBlocking {
        val file = folder.root.resolve("browse.json")
        val first = ChannelBrowseCache(file).get(channels())
        assertEquals(2, first.calculated); assertTrue(first.saved)
        val restarted = ChannelBrowseCache(file).get(channels())
        assertTrue(restarted.diskRead); assertEquals(2, restarted.reused); assertEquals(0, restarted.calculated); assertFalse(restarted.saved)
        assertFalse(file.readText().contains("video.test")); assertFalse(file.readText().contains("secret")); assertFalse(file.readText().contains("翡翠台"))
    }
    @Test fun changesOnlyRebuildAffectedChannelsAndDeletionPurgesOldEntries() = runBlocking {
        val file = folder.root.resolve("browse.json")
        val store = ChannelBrowseCache(file)
        store.get(channels())
        val changed = channels().map { if (it.id == "j") it.copy(group = "韩国") else it }
        val update = store.get(changed)
        assertEquals(1, update.reused); assertEquals(1, update.calculated)
        assertEquals("韩国", update.index.region(changed.last()))
        store.get(changed.take(1))
        val restart = ChannelBrowseCache(file).get(channels())
        assertEquals(1, restart.reused); assertEquals(1, restart.calculated)
    }
    @Test fun telemetryAndReorderDoNotInvalidateClassificationAndCurrentChannelsAreUsed() = runBlocking {
        val file = folder.root.resolve("browse.json")
        val store = ChannelBrowseCache(file)
        store.get(channels())
        val fresh = channels().reversed().map { ch -> ch.copy(streams = ch.streams.map { it.copy(lastSuccessAt = 123L, failureCount = 5) }) }
        val next = store.get(fresh)
        assertEquals(0, next.calculated); assertEquals(2, next.reused); assertFalse(next.saved)
        assertEquals(123L, next.index.channels.first().streams.first().lastSuccessAt)
    }
    @Test fun emptyStartupSnapshotDoesNotErasePersistentOrMemoryCache() = runBlocking {
        val file = folder.root.resolve("browse.json")
        ChannelBrowseCache(file).get(channels())
        val store = ChannelBrowseCache(file)
        assertTrue(store.get(emptyList()).index.channels.isEmpty())
        val next = store.get(channels())
        assertEquals(2, next.reused); assertEquals(0, next.calculated)
    }
    @Test fun urlTokenChangesReuseButProviderClassificationChangesInvalidate() = runBlocking {
        val file = folder.root.resolve("browse.json")
        val store = ChannelBrowseCache(file)
        val base = Channel("a", "地方台", "香港", streams = listOf(Stream("s", "https://video.test/live?token=old", "p")))
        store.get(listOf(base))
        val tokenOnly = base.copy(streams = listOf(base.streams.first().copy(url = "https://video.test/live?token=new")))
        assertEquals(0, store.get(listOf(tokenOnly)).calculated)
        val provider = base.copy(streams = listOf(base.streams.first().copy(url = "https://www.mytvsuper.com/live?token=secret")))
        val changed = store.get(listOf(provider))
        assertEquals(1, changed.calculated)
        assertEquals("TVB", changed.index.type(provider))
    }
    @Test fun corruptVersionedOrUnwritableCacheStillBuildsUsableIndex() = runBlocking {
        val file = folder.root.resolve("browse.json")
        file.writeText("broken")
        assertEquals(2, ChannelBrowseCache(file).get(channels()).calculated)
        assertEquals(2, ChannelBrowseCache(file, ruleVersion = 2).get(channels()).calculated)
        val directory = folder.newFolder("cannot-replace-directory")
        val result = ChannelBrowseCache(directory).get(channels())
        assertEquals(2, result.index.channels.size); assertFalse(result.saved)
    }
}
