package com.tvatlas.core.player

/** Live window positions can rewind when a manifest slides. Measure actual rendered activity instead.
 * A playing flag alone never earns evidence: decoder output must also have advanced in the interval.
 */
class RenderedPlaybackProgress(startMs: Long, initialFrames: Int) {
    private var lastAt = startMs
    private var lastFrames = initialFrames
    var millis: Long = 0
        private set
    fun observe(nowMs: Long, frames: Int, playing: Boolean) {
        val elapsed = (nowMs - lastAt).coerceAtLeast(0)
        if (playing && frames > lastFrames) millis += elapsed
        lastAt = nowMs
        lastFrames = frames
    }
}
