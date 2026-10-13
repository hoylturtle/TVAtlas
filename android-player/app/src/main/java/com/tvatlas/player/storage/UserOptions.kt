package com.tvatlas.player.storage

import kotlinx.serialization.Serializable
import com.tvatlas.core.model.RouteTarget

@Serializable enum class NetworkAccess(val label: String) { WIFI_ONLY("仅 Wi-Fi"), MOBILE_ONLY("仅移动网络"), BOTH("Wi-Fi 与移动网络") }
@Serializable data class UserOptions(
    val favorites: Set<String> = emptySet(), val epgUrl: String = "https://epg.112114.xyz/pp.xml",
    val autoRefresh: Boolean = true, val refreshHours: Int = 24,
    val warnMetered: Boolean = true, val wifiUpdates: Boolean = true, val limitMiB: Long = 0,
    val networkAccess: NetworkAccess = NetworkAccess.BOTH,
)
@Serializable data class SavedPreferences(val options: UserOptions = UserOptions(), val diagnostics: Boolean = false,
    val updateRoute: RouteTarget = RouteTarget.AUTO, val subscriptionRoute: RouteTarget = RouteTarget.AUTO)
