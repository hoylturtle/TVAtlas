package com.tvatlas.core.player

import com.tvatlas.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RouteReliabilityTest {
    private val now = 1000000000L
    private fun route(stream: String, node: String, rule: String = "地区自动路由：香港") =
        RouteAttempt(stream, "https://fixture.test/video", RouteTarget.proxy(node), rule)
    private fun event(route: RouteAttempt, success: Boolean, at: Long = now - 10) =
        RouteObservation("channel", route.streamId, route.target, at, success, if (success) null else FailureKind.NETWORK)
    @Test fun successfulPairsMoveForwardRepeatedFailuresMoveBackWithoutRemovingCandidates() {
        val failed = route("s", "bad"); val unknown = route("s", "new"); val good = route("s", "good")
        val events = (1..3).map { event(failed, false, now - it) } + event(good, true)
        assertEquals(listOf(good, unknown, failed), RouteReliability.order(RoutePlan(listOf(failed, unknown, good)), events, "channel", now).attempts)
    }
    @Test fun manualFirstNodeAndSourceOrderRemainAuthoritative() {
        val manual = route("official", "manual", "线路手动设置")
        val backup = route("official", "backup", "线路手动设置 · 同地区保底")
        val other = route("other", "fast")
        val events = (1..3).map { event(manual, false, now - it) } + event(backup, true) + event(other, true)
        assertEquals(listOf(manual, backup, other), RouteReliability.order(RoutePlan(listOf(manual, backup, other)), events, "channel", now).attempts)
    }
    @Test fun staleFutureAndOtherChannelResultsDoNotAffectRanking() {
        val first = route("s", "first"); val second = route("s", "second")
        val plan = RoutePlan(listOf(first, second))
        val events = listOf(event(second, true, now - RouteReliability.WINDOW_MS - 1), event(second, true, now + 1), event(second, true).copy(channelId = "other"))
        assertEquals(plan, RouteReliability.order(plan, events, "channel", now))
    }
    @Test fun statisticsUseAtMostTwentyRecentResultsPerPair() {
        val r = route("s", "node")
        val events = (1..30).map { event(r, it <= 10, now - it) }
        val record = RouteReliability.records(events, "channel", now).single()
        assertEquals(10, record.successes); assertEquals(10, record.failures); assertEquals(50, record.successPercent)
    }
    @Test fun progressCountsActualAttemptsWhenInvalidSourceSkipsCandidates() {
        val a = route("s", "a"); val b = route("s", "b"); val c = route("other", "c")
        val session = FailoverSession(RoutePlan(listOf(a, b, c)))
        assertEquals(3, session.candidateCount); assertEquals(a, session.start()); assertEquals(1, session.attemptCount)
        assertEquals(c, session.fail(PlaybackFailure(FailureKind.MANIFEST))); assertEquals(2, session.attemptCount)
        assertNull(session.fail(PlaybackFailure(FailureKind.NETWORK))); assertEquals(2, session.attemptCount)
        session.stop(); assertNull(session.start())
    }
}
