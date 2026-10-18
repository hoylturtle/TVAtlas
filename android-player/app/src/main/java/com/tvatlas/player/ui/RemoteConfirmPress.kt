package com.tvatlas.player.ui

internal enum class ConfirmAction { NONE, SHORT, LONG }

/** Activate on release so a held confirmation cannot spill into a newly opened panel. */
internal class RemoteConfirmPress {
    private var armed = false
    private var longHandled = false
    fun event(down: Boolean, repeatCount: Int = 0, longPress: Boolean = false): ConfirmAction {
        if (down) {
            if (repeatCount == 0) { armed = true; longHandled = false }
            if (armed && longPress && !longHandled) { longHandled = true; return ConfirmAction.LONG }
            return ConfirmAction.NONE
        }
        val action = if (armed && !longHandled) ConfirmAction.SHORT else ConfirmAction.NONE
        armed = false
        longHandled = false
        return action
    }
    fun cancel() { armed = false; longHandled = false }
}
