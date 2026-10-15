package com.tvatlas.player.playback

import com.tvatlas.core.model.RouteTarget
import com.tvatlas.core.player.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RouteReliabilityStoreTest {
    @Test fun savesOpaqueRecordsAndCountsOneOutcomePerAttempt() = runBlocking {
        val dir = Files.createTempDirectory("route-records").toFile()
        try {
            val file = java.io.File(dir, "history.json")
            val store = RouteReliabilityStore(file)
            val event = RouteObservation("channel", "stream", RouteTarget.proxy("node"), 100000, true, attemptId = "session:1")
            store.record(event); store.record(event.copy(at = 100001, success = false, failure = FailureKind.SEGMENT))
            assertEquals(1, store.state.value.size)
            assertFalse(RouteReliabilityStore(file).load().single().success)
            assertFalse(file.readText().contains("https://"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun damagedHistoryIsPreservedAndCanBeIgnoredByPlayback() = runBlocking {
        val dir = Files.createTempDirectory("route-records").toFile()
        try {
            val file = java.io.File(dir, "history.json").apply { writeText("damaged") }
            val store = RouteReliabilityStore(file)
            assertTrue(runCatching { store.load() }.isFailure)
            assertTrue(runCatching { store.record(RouteObservation("c", "s", RouteTarget.DIRECT, 1, true)) }.isFailure)
            assertEquals("damaged", file.readText())
        } finally { dir.deleteRecursively() }
    }
}
