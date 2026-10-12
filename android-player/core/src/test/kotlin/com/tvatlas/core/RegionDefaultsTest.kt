package com.tvatlas.core

import com.tvatlas.core.model.*
import com.tvatlas.core.routing.*
import org.junit.Assert.*
import org.junit.Test

class RegionDefaultsTest {
    private val stream = Stream("s", "https://example.com/live.m3u8", "p")
    private val hk = Channel("hk", "翡翠台", "香港 · 综合", streams = listOf(stream))
    private val nodes = listOf(
        ProxyProfile("us", "美国", ProxyType.MIHOMO, "127.0.0.1", 7890),
        ProxyProfile("hk1", "🇭🇰 ②香港 | HKG", ProxyType.MIHOMO, "127.0.0.1", 7890),
        ProxyProfile("hk2", "Hong Kong 02", ProxyType.MIHOMO, "127.0.0.1", 7890),
        ProxyProfile("off", "HK 03", ProxyType.MIHOMO, "127.0.0.1", 7890, enabled = false),
    )
    @Test fun regionOrderingAndNodeNames() {
        val mainland = hk.copy(id = "cn", group = "中国大陆 · 新闻")
        val pearl = hk.copy(id = "pearl", group = "香港 · 新闻")
        assertEquals(listOf(hk, pearl, mainland), listOf(mainland, hk, pearl).hongKongFirst())
        for (name in listOf("香港", "🇭🇰", "HKG 01", "HK-02", "Hong Kong")) assertTrue(isHongKongLabel(name))
        assertFalse(isHongKongLabel("SHK server"))
        assertTrue(hk.copy(group = "其他", tvgId = "Jade.hk").isHongKong())
    }
    @Test fun legacyBareHongKongAndGenreAliasesNormalizeWithoutChangingRoutes() {
        val manual = RouteTarget.proxy("hk1")
        val original = hk.copy(group = "香港", manualRoute = manual)
        assertEquals("香港 · 综合", original.normalizeHongKongGroup().group)
        assertEquals("香港 · 新闻", original.copy(name = "无线新闻台").normalizeHongKongGroup().group)
        assertEquals("香港 · 影视", original.copy(group = "香港电影").normalizeHongKongGroup().group)
        assertEquals(manual, original.normalizeHongKongGroup().manualRoute)
        assertEquals(original.id, original.normalizeHongKongGroup().id)
        val other = original.copy(group = "台湾 · 新闻")
        assertEquals(other, other.normalizeHongKongGroup())
    }
    @Test fun onlyEnabledHongKongNodesAndOldDirectHistoryCannotOverride() {
        val resolver = DefaultRouteResolver(RuleConfig(defaultRoute = RouteTarget.DIRECT), nodes)
        val plan = resolver.channelPlan(hk, SuccessfulRoute("s", RouteTarget.DIRECT, 1))
        assertEquals(listOf(RouteTarget.proxy("hk1"), RouteTarget.proxy("hk2")), plan.attempts.map { it.target })
        assertEquals("地区自动路由：香港", plan.attempts.first().matchedRule)
        assertEquals(RouteTarget.DIRECT, resolver.resolve(StreamContext(hk.copy(group = "大陆"), stream)).attempts.single().target)
    }
    @Test fun explicitOverridesAndNoHongKongNodeFallback() {
        val resolver = DefaultRouteResolver(RuleConfig(), nodes)
        assertEquals(RouteTarget.DIRECT, resolver.resolve(StreamContext(hk.copy(manualRoute = RouteTarget.DIRECT), stream)).attempts.single().target)
        assertEquals(listOf(RouteTarget.proxy("us"), RouteTarget.proxy("hk1"), RouteTarget.proxy("hk2")),
            resolver.resolve(StreamContext(hk, stream.copy(manualRoute = RouteTarget.proxy("us")))).attempts.map { it.target })
        val rule = Rule("custom", Match(group = hk.group), RouteTarget.proxy("us"))
        assertEquals(RouteTarget.proxy("us"), DefaultRouteResolver(RuleConfig(rules = listOf(rule)), nodes).resolve(StreamContext(hk, stream)).attempts.single().target)
        assertTrue(DefaultRouteResolver(RuleConfig(defaultRoute = RouteTarget.DIRECT), nodes.take(1)).resolve(StreamContext(hk, stream)).attempts.isEmpty())
    }
}
