package com.tvatlas.player.update

import com.tvatlas.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test

class UpdateNetworkTest {
    private val nodes = listOf(
        ProxyProfile("hk", "香港", ProxyType.HTTP, "localhost", 1),
        ProxyProfile("us", "美国", ProxyType.HTTP, "localhost", 2),
        ProxyProfile("off", "香港备用", ProxyType.HTTP, "localhost", 3, enabled = false))
    @Test fun selectedProxyHasBoundedEnabledBackupsWithoutAddingDirect() {
        assertEquals(listOf(RouteTarget.proxy("us"), RouteTarget.proxy("hk")), updateTargets(RouteTarget.proxy("us"), nodes))
        assertEquals(listOf(RouteTarget.DIRECT), updateTargets(RouteTarget.DIRECT, nodes))
        assertEquals(listOf(RouteTarget.DIRECT, RouteTarget.proxy("hk"), RouteTarget.proxy("us")), updateTargets(RouteTarget.AUTO, nodes))
    }
    @Test fun h2BadRequestRetriesSameRouteWithFreshHttp1Client() = runBlocking {
        val protocols = mutableListOf<List<Protocol>>()
        var factories = 0
        val result = withUpdateRoutes(listOf(RouteTarget.proxy("hk")), false, client = { factories++; OkHttpClient() }) { client ->
            protocols += client.protocols
            if (protocols.size == 1) throw UpdateException("fixture", 400, Protocol.HTTP_2)
            "verified"
        }
        assertEquals("verified", result); assertEquals(1, factories)
        assertEquals(listOf(Protocol.HTTP_1_1), protocols.last())
    }
    @Test fun failureFallsBackToAnotherNodeAndCancellationStopsTheChain() = runBlocking {
        val seen = mutableListOf<RouteTarget>()
        val result = withUpdateRoutes(updateTargets(RouteTarget.proxy("hk"), nodes), false, client = { seen += it; OkHttpClient() }) {
            if (seen.size == 1) throw UpdateException("blocked", 400, Protocol.HTTP_1_1)
            "verified"
        }
        assertEquals("verified", result); assertEquals(listOf(RouteTarget.proxy("hk"), RouteTarget.proxy("us")), seen)
        seen.clear()
        try {
            withUpdateRoutes(updateTargets(RouteTarget.proxy("hk"), nodes), false, client = { seen += it; OkHttpClient() }) {
                throw CancellationException("cancelled")
            }; fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, seen.size) }
    }
}
