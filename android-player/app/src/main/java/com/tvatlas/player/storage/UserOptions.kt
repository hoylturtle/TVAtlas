package com.tvatlas.player.storage

import kotlinx.serialization.Serializable
import com.tvatlas.core.model.RouteTarget

@Serializable data class UserOptions(
    val favorites: Set<String> = emptySet(), val epgUrl: String = "https://epg.112114.xyz/pp.xml",
    val autoRefresh: Boolean = true, val refreshHours: Int = 24,
    val warnMetered: Boolean = true, val wifiUpdates: Boolean = true, val limitMiB: Long = 0,
)
@Serializable data class SavedPreferences(val options: UserOptions = UserOptions(), val diagnostics: Boolean = false,
    val updateRoute: RouteTarget = RouteTarget.AUTO, val subscriptionRoute: RouteTarget = RouteTarget.AUTO)
