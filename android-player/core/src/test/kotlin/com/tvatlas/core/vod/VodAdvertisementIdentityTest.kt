package com.tvatlas.core.vod

import org.junit.Assert.*
import org.junit.Test

class VodAdvertisementIdentityTest {
    @Test fun rotatingAddressesRetainReportOnlyForSameEpisodeAndProviderLine() {
        val old = VodItem.episode("https://example.com/old?token=1", "片名", "movie:1", "provider:a")
        val renewed = VodItem.episode("https://example.com/new?token=2", "片名", "movie:1", "provider:a")
        val otherProvider = VodItem.episode("https://example.com/old?token=1", "片名", "movie:1", "provider:b")
        val otherEpisode = VodItem.episode("https://example.com/old?token=1", "片名", "movie:2", "provider:a")
        assertEquals(old.advertisementKey, renewed.advertisementKey)
        assertNotEquals(old.advertisementKey, otherProvider.advertisementKey)
        assertNotEquals(old.advertisementKey, otherEpisode.advertisementKey)
        assertEquals(old.key, otherProvider.key)
        assertNotEquals(old.key, otherEpisode.key)
    }
}
