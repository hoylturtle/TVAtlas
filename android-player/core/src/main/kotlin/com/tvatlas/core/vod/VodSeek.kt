package com.tvatlas.core.vod

/** Unknown stream duration must not turn a forward seek into a restart at zero. */
object VodSeek {
    fun target(position: Long, delta: Long, duration: Long): Long {
        val start = position.coerceAtLeast(0)
        val target = if (delta > 0 && start > Long.MAX_VALUE - delta) Long.MAX_VALUE else (start + delta).coerceAtLeast(0)
        return if (duration > 0) target.coerceAtMost(duration) else target
    }
}
