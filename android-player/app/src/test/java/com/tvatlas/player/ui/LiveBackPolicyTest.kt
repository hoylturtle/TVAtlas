package com.tvatlas.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBackPolicyTest {
    @Test fun tvPlaybackAndStoppedHomeOpenMenuWithoutExit() {
        assertEquals(LiveBackAction.SHOW_MENU, liveBackAction(true, true, true, false))
        assertEquals(LiveBackAction.SHOW_MENU, liveBackAction(true, false, false, true))
    }
    @Test fun tvChannelOverlayClosesBeforeOpeningMenu() {
        assertEquals(LiveBackAction.HIDE_CHANNELS, liveBackAction(true, false, true, true))
        assertEquals(LiveBackAction.SHOW_MENU, liveBackAction(true, true, true, false))
    }
    @Test fun portraitAndPhoneFullscreenRetainTheirBackHierarchy() {
        assertEquals(LiveBackAction.SHOW_CHANNELS, liveBackAction(false, true, true, false))
        assertEquals(LiveBackAction.CONFIRM_EXIT, liveBackAction(false, false, false, true))
    }
}
