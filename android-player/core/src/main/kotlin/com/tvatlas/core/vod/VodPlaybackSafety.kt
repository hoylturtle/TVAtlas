package com.tvatlas.core.vod

import kotlinx.serialization.Serializable

@Serializable data class VodSkipRange(val startMs: Long, val endMs: Long)
object VodPlaybackSafety {
    fun validateSkip(startMs: Long, endMs: Long, durationMs: Long): VodSkipRange {
        require(durationMs > 0 && startMs >= 0 && endMs > startMs && endMs - startMs >= 1000 && endMs <= durationMs) { "跳过区间须在视频时长内，且至少 1 秒" }
        return VodSkipRange(startMs, endMs)
    }
    fun merge(ranges: List<VodSkipRange>): List<VodSkipRange> {
        val result = mutableListOf<VodSkipRange>()
        ranges.sortedBy { it.startMs }.forEach { range ->
            val previous = result.lastOrNull()
            if (previous != null && range.startMs <= previous.endMs) result[result.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, range.endMs))
            else result += range
        }
        return result
    }
    fun needsResumeConfirmation(oldDuration: Long, newDuration: Long): Boolean = oldDuration > 0 && newDuration > 0 &&
        kotlin.math.abs(oldDuration - newDuration) > maxOf(30_000L, oldDuration / 20)
}
