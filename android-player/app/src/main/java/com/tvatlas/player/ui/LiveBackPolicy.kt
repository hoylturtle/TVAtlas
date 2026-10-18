package com.tvatlas.player.ui

internal enum class LiveBackAction { HIDE_CHANNELS, SHOW_CHANNELS, SHOW_MENU, CONFIRM_EXIT }

/** Root live handling only; child dialogs and the programme panel consume Back first. */
internal fun liveBackAction(wide: Boolean, immersive: Boolean, active: Boolean, channelsOpen: Boolean): LiveBackAction = when {
    wide && !immersive && active && channelsOpen -> LiveBackAction.HIDE_CHANNELS
    wide -> LiveBackAction.SHOW_MENU
    immersive -> LiveBackAction.SHOW_CHANNELS
    else -> LiveBackAction.CONFIRM_EXIT
}
