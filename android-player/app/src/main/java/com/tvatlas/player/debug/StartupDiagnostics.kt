package com.tvatlas.player.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One outstanding heartbeat; capture the blocked main-thread stack from a worker, never per-frame logging. */
class StartupDiagnostics(private val log: DebugLog) {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingAt = AtomicLong(0)
    private val active = AtomicBoolean(false)
    private val acknowledged = Runnable {
        val sent = pendingAt.getAndSet(0)
        val elapsed = if (sent == 0L) 0 else SystemClock.uptimeMillis() - sent
        if (elapsed >= 700) log.event("WARN", "MAIN_RECOVERED", "heartbeatDelayMs=$elapsed")
    }
    init {
        Thread({
            var reported = false
            while (true) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                if (!active.get()) continue
                val at = pendingAt.get()
                if (at == 0L) {
                    reported = false
                    if (pendingAt.compareAndSet(0, SystemClock.uptimeMillis())) handler.post(acknowledged)
                } else if (!reported && SystemClock.uptimeMillis() - at >= 700) {
                    reported = true
                    val stack = Looper.getMainLooper().thread.stackTrace.take(12).joinToString(" | ") {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                    }
                    log.event("WARN", "MAIN_STALL", "blockedMs=${SystemClock.uptimeMillis() - at} stack=$stack")
                }
            }
        }, "TVAtlas-main-watchdog").apply { isDaemon = true; start() }
    }
    fun foreground(value: Boolean) {
        active.set(value)
        if (!value) { handler.removeCallbacks(acknowledged); pendingAt.set(0) }
    }
}
