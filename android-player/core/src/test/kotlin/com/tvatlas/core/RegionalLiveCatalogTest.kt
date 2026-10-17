package com.tvatlas.core

import com.tvatlas.core.model.*
import com.tvatlas.core.playlist.*
import com.tvatlas.core.routing.DefaultRouteResolver
import com.tvatlas.core.routing.RuleConfig
import org.junit.Assert.*
import org.junit.Test

class RegionalLiveCatalogTest {
    private fun channel(name: String, region: String, url: String) =
        M3uParser.parse("#EXTINF:-1 group-title=\"$region · 综合\",$name\n$url\n", "built-in").single()

    @Test fun replacesOnlyMatchingRegionalChannelsAndRejectsMainlandOrHongKongRepairs() {
        val hk = channel("翡翠台", "香港", "https://official.test/jade")
        val cn = channel("CCTV-1", "中国大陆", "https://old.test/cctv")
        val mo = channel("澳视澳门", "澳门", "https://old.test/mo")
        val user = channel("台视", "台湾", "https://user.test/ttv")
        val fixed = channel("澳视澳门", "澳门", "https://new.test/mo")
        val merged = RegionalLiveCatalog.merge(listOf(hk, cn, mo, user), listOf(fixed,
            channel("翡翠台", "香港", "https://bad.test/jade"), channel("CCTV-1", "中国大陆", "https://bad.test/cctv")))
        assertEquals(listOf(hk, cn, user, fixed), merged)
        assertEquals(merged, RegionalLiveCatalog.merge(merged, listOf(fixed)))
    }

    @Test fun newCountriesUseMatchingNodesOrDirectAndRespectManualRoutes() {
        for ((label, code) in listOf("新加坡" to "SG", "美国" to "US", "英国" to "GB", "泰国" to "TH")) {
            val ch = channel("News", label, "https://example.test/live")
            val node = ProxyProfile("regional", "$code 01", ProxyType.MIHOMO, "host", 443)
            val wrong = node.copy(id = "wrong", name = "香港")
            assertEquals(label, ch.playbackRegion()!!.label)
            assertEquals(RouteTarget.DIRECT, DefaultRouteResolver(RuleConfig(), listOf(wrong)).channelPlan(ch, null).attempts.single().target)
            assertEquals(RouteTarget.proxy(node.id), DefaultRouteResolver(RuleConfig(), listOf(wrong, node)).channelPlan(ch, null).attempts.single().target)
            assertEquals(RouteTarget.DIRECT, DefaultRouteResolver(RuleConfig(), listOf(node)).channelPlan(ch.copy(manualRoute = RouteTarget.DIRECT), null).attempts.single().target)
        }
    }
}
