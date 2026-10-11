package com.tvatlas.core.player

import com.tvatlas.core.model.*

enum class FailureKind { NETWORK, HTTP, MANIFEST, SEGMENT, DECODER }
data class PlaybackFailure(val kind: FailureKind, val httpStatus: Int? = null) {
    val skipStream: Boolean get() = httpStatus in listOf(404, 410) || kind in listOf(FailureKind.MANIFEST, FailureKind.DECODER)
}
class FailoverSession(plan: RoutePlan) {
    private val remaining = plan.attempts.toMutableList()
    var current: RouteAttempt? = null
        private set
    var stopped = false
        private set
    fun start(): RouteAttempt? = next()
    private fun next(): RouteAttempt? {
        current = if (stopped || remaining.isEmpty()) null else remaining.removeAt(0)
        return current
    }
    fun fail(failure: PlaybackFailure): RouteAttempt? {
        if (stopped) return null
        if (failure.skipStream) remaining.removeAll { it.streamId == current?.streamId }
        return next()
    }
    fun stop() { stopped = true; current = null; remaining.clear() }
}

/** Success requires continuous ready playback and bytes received after readiness. */
class SuccessGate(private val durationMs: Long = 3000) {
    private var readyAt: Long? = null
    private var bytesAtReady = 0L
    private var recorded = false
    fun update(now: Long, playing: Boolean, bytes: Long): Boolean {
        if (recorded) return false
        if (!playing) { readyAt = null; return false }
        val start = readyAt ?: now.also { readyAt = it; bytesAtReady = bytes }
        if (now - start >= durationMs && bytes > bytesAtReady) { recorded = true; return true }
        return false
    }
}
