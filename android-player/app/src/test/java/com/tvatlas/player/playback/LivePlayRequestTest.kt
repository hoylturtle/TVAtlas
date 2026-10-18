package com.tvatlas.player.playback

import org.junit.Assert.*
import org.junit.Test

class LivePlayRequestTest {
    @Test fun retainsConnectingRequestBeforePlayerExists() {
        assertTrue(LivePlayRequest.shouldReuse("c", null, PlaybackStatus("c", loading = true), false, false))
    }
    @Test fun retainsPlayingRequestButAllowsExplicitRestart() {
        val status = PlaybackStatus("c", successAt = 1)
        assertTrue(LivePlayRequest.shouldReuse("c", null, status, true, false))
        assertFalse(LivePlayRequest.shouldReuse("c", null, status, true, true))
    }
    @Test fun permitsDifferentChannelLineStoppedAndFailedRetries() {
        val status = PlaybackStatus("c", selectedStreamId = "s", loading = true)
        assertFalse(LivePlayRequest.shouldReuse("other", "s", status, false, false))
        assertFalse(LivePlayRequest.shouldReuse("c", "other", status, false, false))
        assertFalse(LivePlayRequest.shouldReuse("c", "s", status.copy(error = "failed"), true, false))
        assertFalse(LivePlayRequest.shouldReuse("c", null, PlaybackStatus("c"), false, false))
    }
}
