package com.tvatlas.core.vod

import com.tvatlas.core.model.RouteTarget
import org.junit.Assert.*
import org.junit.Test

class VodSourceRoutesTest {
    @Test fun manualProxyDoesNotLeakIntoOtherSources() {
        val routes = VodSourceRoutes()
        routes.set("source-a", RouteTarget.proxy("node-a"))
        assertEquals(RouteTarget.DIRECT, routes.get("source-b"))
        assertEquals(RouteTarget.proxy("node-a"), routes.get("source-a"))
        routes.set("source-a", RouteTarget.DIRECT)
        assertEquals(RouteTarget.DIRECT, routes.get("source-a"))
    }
    @Test(expected = IllegalArgumentException::class) fun neverUsesImplicitFallback() {
        VodSourceRoutes().set("source", RouteTarget.AUTO)
    }
}
