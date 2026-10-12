package com.tvatlas.core

import com.tvatlas.core.model.*
import com.tvatlas.core.routing.*
import com.tvatlas.core.player.*
import org.junit.Assert.*
import org.junit.Test

class RegionalRoutingTest {
    private val stream = Stream("s", "https://example.com/live", "p")
    private fun channel(group: String) = Channel("c", "test", group, streams = listOf(stream))
    private val nodes = listOf("香港", "台湾 TWN", "日本 JPN", "韩国 KOR").mapIndexed { i, name -> ProxyProfile("$i", name, ProxyType.MIHOMO, "host", 443) }
    @Test fun mapsRegionsAndMainlandDoesNotUseGlobalProxyDefault() {
        val resolver = DefaultRouteResolver(RuleConfig(defaultRoute = RouteTarget.proxy("0")), nodes)
        assertEquals(RouteTarget.DIRECT, resolver.resolve(StreamContext(channel("中国大陆 · 新闻"), stream)).attempts.single().target)
        for ((group, index) in listOf("香港", "澳门", "台灣", "日本", "韓國").zip(listOf(0,0,1,2,3)))
            assertEquals(RouteTarget.proxy("$index"), resolver.resolve(StreamContext(channel(group), stream)).attempts.single().target)
        assertEquals(PlaybackRegion.JAPAN, channel("其他").copy(tvgId = "NHK.jp@HD").playbackRegion())
    }
    @Test fun latencyBeatsHistoryAndDeadNodeFallsBackWithoutCrossingRegion() {
        val extra = nodes[0].copy(id = "fast", name = "HK 02")
        val now = 1000L
        val health = mapOf("0" to NodeHealth(90, now), "fast" to NodeHealth(10, now))
        val resolver = DefaultRouteResolver(RuleConfig(), nodes + extra, health, now)
        val plan = resolver.channelPlan(channel("香港"), SuccessfulRoute("s", RouteTarget.proxy("0"), 1))
        assertEquals(listOf("fast", "0"), plan.attempts.map { it.target.profile })
        val session = FailoverSession(plan)
        assertEquals("fast", session.start()!!.target.profile)
        assertEquals("0", session.fail(PlaybackFailure(FailureKind.NETWORK))!!.target.profile)
        assertNull(session.fail(PlaybackFailure(FailureKind.NETWORK)))
        val dead = resolver.run { DefaultRouteResolver(RuleConfig(), nodes + extra, health + ("fast" to NodeHealth(null, now)), now) }
        assertEquals("0", dead.channelPlan(channel("香港"), null).attempts.first().target.profile)
    }
    @Test fun forcedRoutesAndCustomRulesRemainAuthoritative() {
        val ch = channel("日本")
        assertEquals(RouteTarget.DIRECT, DefaultRouteResolver(RuleConfig(), nodes).resolve(StreamContext(ch.copy(manualRoute = RouteTarget.DIRECT), stream)).attempts.single().target)
        val config = RuleConfig(rules = listOf(Rule("custom", Match(group = ch.group), RouteTarget.proxy("1"))))
        assertEquals("1", DefaultRouteResolver(config, nodes).resolve(StreamContext(ch, stream)).attempts.single().target.profile)
        assertTrue(DefaultRouteResolver(RuleConfig(), nodes.take(1)).channelPlan(ch, null).attempts.isEmpty())
    }
    @Test fun manualNodeIsFirstThenSameRegionAndChildRequestsKeepFallback() {
        val second = nodes[0].copy(id = "hk2", name = "香港 2")
        val ch = channel("香港").copy(manualRoute = RouteTarget.proxy("0"))
        val resolver = DefaultRouteResolver(RuleConfig(), nodes + second)
        val plan = resolver.channelPlan(ch, SuccessfulRoute("s", RouteTarget.proxy("hk2"), 1))
        assertEquals(listOf("0", "hk2"), plan.attempts.map { it.target.profile })
        val failover = FailoverSession(plan)
        failover.start()
        val fallback = failover.fail(PlaybackFailure(FailureKind.NETWORK))!!
        assertEquals(RouteTarget.proxy("hk2"), resolver.requestTarget(StreamContext(ch, stream), "https://cdn.test/segment", fallback.target))
        assertTrue(fallback.matchedRule.contains("同地区保底"))
    }
    @Test fun removedManualNodeUsesRegionFallbackAndProvisioningHasBoundedIndependentRoutes() {
        val resolver = DefaultRouteResolver(RuleConfig(), nodes)
        val ch = channel("香港").copy(manualRoute = RouteTarget.proxy("removed"))
        assertEquals("0", resolver.channelPlan(ch, null).attempts.single().target.profile)
        assertEquals(listOf(RouteTarget.proxy("0"), RouteTarget.DIRECT), resolver.provisioningTargets(StreamContext(ch, stream), RouteTarget.proxy("0")))
        assertEquals(listOf(RouteTarget.DIRECT), resolver.channelPlan(ch.copy(manualRoute = RouteTarget.DIRECT), null).attempts.map { it.target })
    }

}
