package com.tvatlas.core.player

/** Conservative admission, not a promise that every channel/device will remain smooth. */
object TranslationGate {
    const val POLICY = 2
    data class Metrics(val extraMemoryMiB: Long, val extraCpuPercent: Double, val p95LatencyMs: Long,
        val baselineDropRate: Double, val workDropRate: Double, val renderedFrames: Int,
        val samples: Int, val baselineProgressMs: Long, val workProgressMs: Long, val bufferedMs: Long)
    fun rejection(m: Metrics): String? = when {
        !m.extraCpuPercent.isFinite() || !m.baselineDropRate.isFinite() || !m.workDropRate.isFinite() ||
            m.extraMemoryMiB < 0 || m.extraCpuPercent < 0 || m.baselineDropRate < 0 || m.workDropRate < 0 -> "资源测量无效，请重测"
        m.samples < 10 -> "翻译样本不足，请在直播画面重测"
        m.renderedFrames < 100 -> "视频渲染帧不足，请保持直播画面可见后重测"
        m.baselineProgressMs < 10500 || m.workProgressMs < 21000 -> "视频未持续输出，测试无效，请保持直播画面可见后重测"
        m.baselineDropRate > .03 -> "原直播已有明显掉帧，请换一条稳定线路后重测"
        m.bufferedMs > 1000 -> "测试中出现缓冲，请换一条稳定线路后重测"
        m.extraMemoryMiB > 96 -> "翻译额外内存占用超过 96 MiB"
        m.extraCpuPercent > 15 -> "翻译额外 CPU 占用偏高"
        m.p95LatencyMs > 1500 -> "字幕翻译速度不足"
        m.workDropRate > .03 || m.workDropRate - m.baselineDropRate > .015 -> "翻译期间掉帧增加"
        else -> null
    }
}
