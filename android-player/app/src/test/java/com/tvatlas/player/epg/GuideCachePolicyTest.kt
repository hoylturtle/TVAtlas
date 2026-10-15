package com.tvatlas.player.epg

import com.tvatlas.core.epg.*
import com.tvatlas.core.model.Channel
import org.junit.Assert.*
import org.junit.Test

class GuideCachePolicyTest {
    private val now = 1_800_000_000_000L
    private val channel = Channel("local", "河北卫视", "中国大陆 · 卫视", tvgId = "Hebei", streams = emptyList())
    @Test fun onlyImportedChannelsAndCurrentOrNextDayAreRetained() {
        val programmes = listOf(
            Programme("Hebei@HD", now - 1000, now + 1000, "current"),
            Programme("Hebei@HD", now + 1000, now + 2000, "next"),
            Programme("Hebei@HD", now - 2000, now - 1, "expired"),
            Programme("Hebei@HD", now + 2 * 86400000L, now + 3 * 86400000L, "too far"),
            Programme("Unrelated", now, now + 1000, "unrelated"))
        val kept = GuideCachePolicy.retain(Guide(mapOf("Hebei@HD" to listOf("河北卫视"), "Unrelated" to listOf("其他")), programmes), listOf(channel), now)
        assertEquals(listOf("current", "next"), kept.programmes.map { it.title })
        assertEquals(setOf("Hebei@HD"), kept.names.keys)
    }
    @Test fun globalLimitKeepsCurrentProgrammesForEveryChannelBeforeLaterSlots() {
        val channels = (0 until 100).map { channel.copy(id = "local$it", tvgId = "guide$it", name = "频道$it") }
        val programmes = channels.flatMap { c -> (0 until 100).map { n ->
            Programme(c.tvgId!!, now + n * 1000, now + n * 1000 + 1000, "$n")
        } }
        val result = GuideCachePolicy.retain(Guide(programmes = programmes), channels, now)
        assertEquals(GuideCachePolicy.MAX_PROGRAMMES, result.programmes.size)
        assertEquals(100, result.programmes.filter { it.title == "0" }.size)
        assertTrue(result.programmes.groupBy { it.channel }.values.all { it.size <= GuideCachePolicy.MAX_PER_CHANNEL })
    }
    @Test fun noImportedChannelsDoesNotKeepUnrelatedGuide() {
        assertTrue(GuideCachePolicy.retain(Guide(programmes = listOf(Programme("x", now, now + 1000, "x"))), emptyList(), now).programmes.isEmpty())
    }
}
