package com.tvatlas.player.network

import com.tvatlas.player.storage.NetworkAccess
import org.junit.Assert.*
import org.junit.Test

class NetworkPolicyTest {
    @Test fun phoneAllowsExactlyTheConfiguredNetworkTypes() {
        assertTrue(networkAllowed(NetworkAccess.WIFI_ONLY, false, false))
        assertFalse(networkAllowed(NetworkAccess.WIFI_ONLY, true, false))
        assertFalse(networkAllowed(NetworkAccess.MOBILE_ONLY, false, false))
        assertTrue(networkAllowed(NetworkAccess.MOBILE_ONLY, true, false))
        for (mobile in listOf(false, true)) assertTrue(networkAllowed(NetworkAccess.BOTH, mobile, false))
    }
    @Test fun televisionsBypassPhoneRestrictionAndNeverAskForMobileConsent() {
        for (access in NetworkAccess.entries) for (mobile in listOf(false, true)) {
            assertTrue(networkAllowed(access, mobile, true))
            assertFalse(needsMobileConsent(true, mobile, true, false))
        }
    }
    @Test fun mobileConsentOnlyAppliesUntilConfirmedAndNotToWifi() {
        assertTrue(needsMobileConsent(false, true, true, false))
        assertFalse(needsMobileConsent(false, false, true, false))
        assertFalse(needsMobileConsent(false, true, true, true))
        assertFalse(needsMobileConsent(false, true, false, false))
    }
}
