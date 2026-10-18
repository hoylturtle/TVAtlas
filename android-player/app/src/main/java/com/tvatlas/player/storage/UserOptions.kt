package com.tvatlas.player.storage

import kotlinx.serialization.Serializable
import com.tvatlas.core.model.RouteTarget

@Serializable enum class NetworkAccess(val label: String) { WIFI_ONLY("仅 Wi-Fi"), MOBILE_ONLY("仅移动网络"), BOTH("Wi-Fi 与移动网络") }
@Serializable enum class DisplayMode(val label: String) { AUTO("自动"), TV("电视"), TOUCH("手机／平板");
    fun television(systemTelevision: Boolean): Boolean = when (this) { AUTO -> systemTelevision; TV -> true; TOUCH -> false }
}
@Serializable data class UserOptions(
    val favorites: Set<String> = emptySet(), val epgUrl: String = "https://epg.112114.xyz/pp.xml",
    val autoRefresh: Boolean = true, val refreshHours: Int = 24,
    val warnMetered: Boolean = true, val wifiUpdates: Boolean = true, val limitMiB: Long = 0,
    val networkAccess: NetworkAccess = NetworkAccess.BOTH,
    val displayMode: DisplayMode = DisplayMode.AUTO,
    val alwaysLandscape: Boolean = false,
    val liveSubtitles: Boolean = false,
    val vodAutoNext: Boolean = true,
)
@Serializable data class SavedPreferences(val options: UserOptions = UserOptions(), val diagnostics: Boolean = false,
    val updateRoute: RouteTarget = RouteTarget.AUTO, val subscriptionRoute: RouteTarget = RouteTarget.AUTO)
