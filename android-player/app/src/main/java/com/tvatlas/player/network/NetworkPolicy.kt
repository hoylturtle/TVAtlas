package com.tvatlas.player.network

import com.tvatlas.player.storage.NetworkAccess

/** VPN inherits the effective network's metered status when transports are not exposed. */
internal fun networkAllowed(access: NetworkAccess, mobile: Boolean, television: Boolean): Boolean =
    television || when (access) {
        NetworkAccess.BOTH -> true
        NetworkAccess.WIFI_ONLY -> !mobile
        NetworkAccess.MOBILE_ONLY -> mobile
    }

internal fun needsMobileConsent(television: Boolean, mobile: Boolean, warning: Boolean, allowedThisSession: Boolean) =
    !television && mobile && warning && !allowedThisSession
