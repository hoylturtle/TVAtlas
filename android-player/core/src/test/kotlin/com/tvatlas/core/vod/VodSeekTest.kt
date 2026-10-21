package com.tvatlas.core.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class VodSeekTest {
    @Test fun unknownDurationDoesNotResetForwardSeekToZero() {
        assertEquals(90_000L, VodSeek.target(60_000, 30_000, -9223372036854775807L))
        assertEquals(90_000L, VodSeek.target(60_000, 30_000, 0))
    }
    @Test fun rewindAndForwardStayInsideKnownMediaWindow() {
        assertEquals(0L, VodSeek.target(5_000, -30_000, 120_000))
        assertEquals(120_000L, VodSeek.target(110_000, 30_000, 120_000))
        assertEquals(50_000L, VodSeek.target(60_000, -10_000, 120_000))
    }
}
