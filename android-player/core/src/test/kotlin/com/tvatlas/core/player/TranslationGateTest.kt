package com.tvatlas.core.player

import org.junit.Assert.*
import org.junit.Test

class TranslationGateTest {
    private val good = TranslationGate.Metrics(40, 3.0, 200, .001, .002, 800, 25, 15000, 30000, 0)
    @Test fun stableWorkloadPassesButMissingPlaybackEvidenceNeverDoes() {
        assertNull(TranslationGate.rejection(good))
        assertNotNull(TranslationGate.rejection(good.copy(renderedFrames = 0)))
        assertNotNull(TranslationGate.rejection(good.copy(samples = 0)))
        assertNotNull(TranslationGate.rejection(good.copy(workProgressMs = 100)))
        assertNotNull(TranslationGate.rejection(good.copy(extraCpuPercent = Double.NaN)))
        assertNotNull(TranslationGate.rejection(good.copy(extraMemoryMiB = -1)))
    }
    @Test fun unhealthyBaselineCannotApproveEvenIfTranslationAddsNoDrops() {
        assertNotNull(TranslationGate.rejection(good.copy(baselineDropRate = .1, workDropRate = .1)))
        assertNotNull(TranslationGate.rejection(good.copy(bufferedMs = 2000)))
    }
    @Test fun memoryCpuLatencyAndIncrementalFrameLossEachBlockAdmission() {
        for (m in listOf(good.copy(extraMemoryMiB = 97), good.copy(extraCpuPercent = 16.0),
            good.copy(p95LatencyMs = 1501), good.copy(workDropRate = .025))) assertNotNull(TranslationGate.rejection(m))
    }
}
