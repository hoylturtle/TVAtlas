package com.tvatlas.core.player

import com.tvatlas.core.model.*
import kotlinx.serialization.Serializable

@Serializable data class RouteObservation(val channelId: String, val streamId: String, val target: RouteTarget,
    val at: Long, val success: Boolean, val failure: FailureKind? = null, val attemptId: String? = null)
data class RouteRecord(val streamId: String, val target: RouteTarget, val successes: Int, val failures: Int,
    val last: RouteObservation) {
    val successPercent: Int get() = successes * 100 / (successes + failures).coerceAtLeast(1)
}

/** Observations are evidence about this source/node pair, never about other channels or region access. */
object RouteReliability {
    const val WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    fun records(events: List<RouteObservation>, channelId: String, now: Long): List<RouteRecord> = events
        .filter { it.channelId == channelId && it.at in (now - WINDOW_MS)..now }
        .sortedByDescending { it.at }.groupBy { it.streamId to it.target }.map { (key, history) ->
            val recent = history.take(20)
            RouteRecord(key.first, key.second, recent.count { it.success }, recent.count { !it.success }, recent.first())
        }
    fun order(plan: RoutePlan, events: List<RouteObservation>, channelId: String, now: Long): RoutePlan {
        val records = records(events, channelId, now).associateBy { it.streamId to it.target }
        fun rank(attempt: RouteAttempt): Int {
            val record = records[attempt.streamId to attempt.target] ?: return 1
            return if (record.last.success) 0 else if (record.failures >= 3 && record.successPercent <= 33) 2 else 1
        }
        // Keep source preference (including official sources), user rules, and manually selected first nodes.
        return RoutePlan(plan.attempts.groupBy { it.streamId }.values.flatMap { candidates ->
            val (fixed, automatic) = candidates.partition {
                it.matchedRule.contains("手动设置") && !it.matchedRule.contains("同地区保底")
            }
            fixed + automatic.sortedBy(::rank)
        })
    }
}
