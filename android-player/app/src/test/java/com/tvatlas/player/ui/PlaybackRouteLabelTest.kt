package com.tvatlas.player.ui
import com.tvatlas.core.model.*
import com.tvatlas.player.storage.SubscriptionRow
import org.junit.Assert.assertEquals
import org.junit.Test
class PlaybackRouteLabelTest {
    @Test fun labelsIdentifySubscriptionAndConcreteFallbackNode() {
        val nodes = listOf(ProxyProfile("hk2", "香港 2", ProxyType.HTTP, "localhost", 8081, subscriptionId = "s"))
        assertEquals("艾可云 · 香港 2", playbackRouteLabel(RouteTarget.proxy("hk2"), nodes, listOf(SubscriptionRow("s", "艾可云", 0, 2))))
        assertEquals("直连", playbackRouteLabel(RouteTarget.DIRECT, nodes, emptyList()))
    }
}
