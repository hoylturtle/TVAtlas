package com.tvatlas.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteConfirmPressTest {
    @Test fun shortConfirmationOnlyActivatesOnRelease() {
        val press = RemoteConfirmPress()
        assertEquals(ConfirmAction.NONE, press.event(true))
        repeat(4) { assertEquals(ConfirmAction.NONE, press.event(true, it + 1)) }
        assertEquals(ConfirmAction.SHORT, press.event(false))
        assertEquals(ConfirmAction.NONE, press.event(false))
    }
    @Test fun longConfirmationNeverAlsoOpensChannelList() {
        val press = RemoteConfirmPress()
        assertEquals(ConfirmAction.NONE, press.event(true))
        assertEquals(ConfirmAction.LONG, press.event(true, 1, true))
        assertEquals(ConfirmAction.NONE, press.event(true, 2, true))
        assertEquals(ConfirmAction.NONE, press.event(false))
        press.event(true)
        assertEquals(ConfirmAction.SHORT, press.event(false))
    }
    @Test fun losingFocusCannotActivateAnotherPanelOnRelease() {
        val press = RemoteConfirmPress()
        press.event(true)
        press.cancel()
        assertEquals(ConfirmAction.NONE, press.event(false))
        assertEquals(ConfirmAction.NONE, press.event(true, 3, true))
    }
}
