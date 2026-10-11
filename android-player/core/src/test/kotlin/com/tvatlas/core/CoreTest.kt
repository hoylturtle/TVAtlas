package com.tvatlas.core

import com.tvatlas.core.model.*
import com.tvatlas.core.playlist.M3uParser
import com.tvatlas.core.routing.*
import com.tvatlas.core.player.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val us = ProxyProfile("US", "美国", ProxyType.SOCKS5, "127.0.0.1", 7891)
    private val hk = ProxyProfile("HK", "香港", ProxyType.HTTP, "127.0.0.1", 7890)
    private val stream = Stream("s", "https://live.example.com/a.m3u8", "home")
    private val channel = Channel("c", "翡翠台", "香港 · 综合", streams = listOf(stream))
    private fun resolver(rules: List<Rule> = emptyList(), default: RouteTarget = RouteTarget.AUTO) =
        DefaultRouteResolver(RuleConfig(proxies = listOf(us, hk), rules = rules, defaultRoute = default), listOf(us, hk))

    @Test fun chineseBomAndSameNameDeduplication() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:-1 tvg-id=\"jade\" tvg-logo=\"https://x/logo\" group-title=\"香港,综合\",翡翠台\r\nhttps://x/1\n" +
            "#EXTINF:-1,翡翠台\nhttps://x/1\n#EXTINF:-1,翡翠台\n# comment\nhttps://x/2\n"
        val channels = M3uParser.parse(text, "home")
        assertEquals(1, channels.size)
        assertEquals("翡翠台", channels[0].name)
        assertEquals("香港,综合", channels[0].group)
        assertEquals("jade", channels[0].tvgId)
        assertEquals(2, channels[0].streams.size)
        assertEquals(channels[0].id, M3uParser.parse("#EXTINF:-1,翡翠台\nhttps://x/new", "home")[0].id)
    }
    @Test fun malformedAndUnsupportedEntriesCannotLeakMetadata() {
        val c = M3uParser.parse("#EXTINF:-1,wrong\nudp://x\nhttps://x/stray\n#EXTINF:-1,correct\nhttps://x/a", "p")
        assertEquals(listOf("correct"), c.map { it.name })
        assertNull(httpUri("https://user:pass@host/a"))
    }
    @Test fun streamIdsRetainSource() {
        assertNotEquals(M3uParser.parse("#EXTINF:-1,a\nhttps://x/a", "p1")[0].streams[0].id,
            M3uParser.parse("#EXTINF:-1,a\nhttps://x/a", "p2")[0].streams[0].id)
    }
    @Test fun jsonRoundTripAndSchemaValidation() {
        val config = RuleConfig(proxies = listOf(us, hk), rules = listOf(
            Rule("jade", Match(channel = "翡翠台"), RouteTarget(RouteType.AUTO, `try` = listOf("DIRECT", "US", "HK")))))
        assertEquals(config, RuleCodec.parse(RuleCodec.export(config)))
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.parse("""{"schemaVersion":2}""") }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.parse("""{"defaultRoute":{"type":"PROXY","profile":"missing"}}""") }
        assertThrows(Exception::class.java) { RuleCodec.parse("""{"password":"never import plaintext"}""") }
    }
    @Test fun invalidRegexDuplicateProfilesPortsAndEmptyMatchesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.validate(RuleConfig(proxies = listOf(us, us))) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.validate(RuleConfig(proxies = listOf(us.copy(port = 0)))) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.validate(RuleConfig(rules = listOf(Rule("a", Match(), RouteTarget.DIRECT)))) }
        assertThrows(IllegalArgumentException::class.java) { RuleCodec.validate(RuleConfig(rules = listOf(Rule("a", Match(channelRegex = "["), RouteTarget.DIRECT)))) }
    }
    @Test fun allMatchFieldsAndDomainBoundary() {
        assertTrue(Match(channel = "翡翠台", channelRegex = "^翡翠", group = "香港 · 综合", url = stream.url,
            domain = "live.example.com", domainSuffix = "example.com", urlContains = "a.m3u8", playlistId = "home").matches(StreamContext(channel, stream)))
        assertFalse(Match(domainSuffix = "example.com").matches(StreamContext(channel, stream.copy(url = "https://badexample.com/a"))))
    }
    @Test fun priorityOrderBeatsJsonOrder() {
        val r = resolver(listOf(
            Rule("channel", Match(channel = channel.name), RouteTarget.proxy("HK"), -100),
            Rule("domain", Match(domain = "live.example.com"), RouteTarget.proxy("US")),
            Rule("url", Match(url = stream.url), RouteTarget.DIRECT, 999)))
        assertEquals("url", r.resolve(StreamContext(channel, stream)).attempts.single().matchedRule)
    }
    @Test fun sameRankPriorityAndStableJsonOrder() {
        val rules = listOf(Rule("a", Match(group = channel.group), RouteTarget.DIRECT),
            Rule("b", Match(group = channel.group), RouteTarget.proxy("US")))
        assertEquals("a", resolver(rules).resolve(StreamContext(channel, stream)).attempts[0].matchedRule)
        assertEquals("b", resolver(rules.map { if (it.id == "b") it.copy(priority = 1) else it }).resolve(StreamContext(channel, stream)).attempts[0].matchedRule)
    }
    @Test fun manualOverridesAndHistoryCannotOverrideDirect() {
        val r = resolver(listOf(Rule("jade", Match(channel = channel.name), RouteTarget.AUTO)))
        val forced = channel.copy(manualRoute = RouteTarget.DIRECT)
        assertEquals(listOf(RouteTarget.DIRECT), r.channelPlan(forced, SuccessfulRoute("s", RouteTarget.proxy("US"), 1)).attempts.map { it.target })
        assertEquals(RouteTarget.proxy("HK"), r.resolve(StreamContext(forced, stream.copy(manualRoute = RouteTarget.proxy("HK")))).attempts.single().target)
    }
    @Test fun disabledProxyAndUnavailableHistoryAreSkipped() {
        val r = DefaultRouteResolver(RuleConfig(defaultRoute = RouteTarget.AUTO), listOf(us.copy(enabled = false), hk))
        assertEquals(listOf(RouteTarget.DIRECT, RouteTarget.proxy("HK")), r.channelPlan(channel, SuccessfulRoute("s", RouteTarget.proxy("US"), 1)).attempts.map { it.target })
    }
    @Test fun historyPrefersBothStreamAndRoute() {
        val second = stream.copy(id = "s2", url = "https://x/2")
        val p = resolver().channelPlan(channel.copy(streams = listOf(stream, second)), SuccessfulRoute("s2", RouteTarget.proxy("US"), 1))
        assertEquals("s2", p.attempts[0].streamId)
        assertEquals(RouteTarget.proxy("US"), p.attempts[0].target)
        assertEquals(6, p.attempts.size)
    }
    @Test fun hlsChildrenInheritAndRequestDomainOverrides() {
        val r = resolver(listOf(Rule("key", Match(domain = "keys.example.com"), RouteTarget.DIRECT)))
        assertEquals(RouteTarget.proxy("US"), r.requestTarget(StreamContext(channel, stream), "https://cdn.example.com/a.ts", RouteTarget.proxy("US")))
        assertEquals(RouteTarget.DIRECT, r.requestTarget(StreamContext(channel, stream), "https://keys.example.com/key", RouteTarget.proxy("US")))
    }
    @Test fun failoverNetworkTriesOtherProxiesThenNextStream() {
        val c = channel.copy(streams = listOf(stream, stream.copy(id = "s2")))
        val session = FailoverSession(resolver().channelPlan(c, null))
        assertEquals(RouteTarget.DIRECT, session.start()!!.target)
        assertEquals(RouteTarget.proxy("US"), session.fail(PlaybackFailure(FailureKind.HTTP, 403))!!.target)
        assertEquals(RouteTarget.proxy("HK"), session.fail(PlaybackFailure(FailureKind.NETWORK))!!.target)
        assertEquals("s2", session.fail(PlaybackFailure(FailureKind.SEGMENT))!!.streamId)
    }
    @Test fun decoderManifestAndGoneSkipStreamAndStopIsNotFailure() {
        for (failure in listOf(PlaybackFailure(FailureKind.DECODER), PlaybackFailure(FailureKind.MANIFEST), PlaybackFailure(FailureKind.HTTP, 404))) {
            val session = FailoverSession(resolver().channelPlan(channel.copy(streams = listOf(stream, stream.copy(id = "s2"))), null))
            session.start()
            assertEquals("s2", session.fail(failure)!!.streamId)
            session.stop()
            assertNull(session.fail(PlaybackFailure(FailureKind.NETWORK)))
        }
    }
    @Test fun successRequiresReadyDurationAndContinuingMediaBytes() {
        val gate = SuccessGate()
        assertFalse(gate.update(0, true, 100))
        assertFalse(gate.update(3000, true, 100))
        assertTrue(gate.update(3001, true, 200))
        assertFalse(gate.update(4000, true, 300))
        val reset = SuccessGate()
        reset.update(0, true, 100)
        reset.update(2000, false, 200)
        assertFalse(reset.update(4000, true, 300))
        assertFalse(reset.update(5000, true, 400))
        assertTrue(reset.update(7001, true, 500))
    }
}
