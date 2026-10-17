package com.tvatlas.core.player

import org.junit.Assert.*
import org.junit.Test

class RenderedPlaybackProgressTest {
    @Test fun slidingLiveWindowDoesNotEraseContinuousRenderedPlayback() {
        val evidence = RenderedPlaybackProgress(1000, 500)
        // Simulated 30 seconds with a live window reset every 4 seconds. Window positions
        // deliberately aren't an input: only renderer activity establishes playback evidence.
        repeat(60) { tick -> evidence.observe(1500L + tick * 500, 512 + tick * 12, true) }
        assertEquals(30000, evidence.millis)
    }
    @Test fun playingFlagWithoutDecoderOutputNeverEarnsProgress() {
        val evidence = RenderedPlaybackProgress(0, 25)
        repeat(60) { evidence.observe((it + 1L) * 500, 25, true) }
        assertEquals(0, evidence.millis)
    }
    @Test fun pausedAndResetDecoderIntervalsDoNotEarnProgress() {
        val evidence = RenderedPlaybackProgress(0, 10)
        evidence.observe(500, 20, true)
        evidence.observe(1000, 30, false)
        evidence.observe(1500, 0, true)
        evidence.observe(2000, 12, true)
        assertEquals(1000, evidence.millis)
    }
}
