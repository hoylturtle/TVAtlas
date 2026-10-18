package com.tvatlas.player.vod

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VodSourceHealthTest {
    @Test fun failuresCoolDownByRouteExpireAndRecoverWithoutPermanentDisable() = runBlocking {
        val dir = Files.createTempDirectory("source-health").toFile(); var now = 1_000_000L
        try {
            val store = VodSourceHealth(dir.resolve("health.json"), this) { now }
            val api = "https://fixture.test/api?token=private"
            store.record(api, "direct", false); assertTrue(store.eligible(api, "direct"))
            store.record(api, "direct", false); assertFalse(store.eligible(api, "direct"))
            assertTrue(store.eligible(api, "proxy-hk"))
            assertEquals(now + 1_800_000, store.get(api, "direct")!!.retryAt)
            now += 1_800_001; assertTrue(store.eligible(api, "direct"))
            store.record(api, "direct", false); store.record(api, "direct", false)
            assertEquals(now + 7_200_000, store.get(api, "direct")!!.retryAt)
            store.reset(api, "direct"); assertTrue(store.eligible(api, "direct"))
            store.record(api, "direct", true, 42)
            assertEquals(42L, store.get(api, "direct")!!.latencyMs)
            val restored = VodSourceHealth(dir.resolve("health.json"), this) { now }
            assertTrue(restored.eligible(api, "direct")); assertEquals(0, restored.get(api, "direct")!!.failures)
            assertFalse(dir.resolve("health.json").readText().contains("private"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun maximumCooldownAlwaysHasADeadline() = runBlocking {
        val dir = Files.createTempDirectory("health-max").toFile(); var now = 1_000_000L
        try {
            val store = VodSourceHealth(dir.resolve("health.json"), this) { now }
            repeat(5) {
                store.record("source", "route", false); store.record("source", "route", false)
                val at = store.get("source", "route")!!.retryAt
                assertTrue(at - now <= 86_400_000); now = at + 1
                assertTrue(store.eligible("source", "route"))
            }
        } finally { dir.deleteRecursively() }
    }
}
