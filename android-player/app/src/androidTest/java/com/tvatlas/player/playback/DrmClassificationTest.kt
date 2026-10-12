package com.tvatlas.player.playback

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.tvatlas.core.player.FailureKind
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DrmClassificationTest {
    @Test fun licenseHttpStatusDistinguishesRenewalTransportAndPermanentRejection() {
        val spec = DataSpec.Builder().setUri("https://fixture.test/license").build()
        for ((status, expected) in listOf(400 to FailureKind.DRM, 401 to FailureKind.HTTP,
            403 to FailureKind.HTTP, 408 to FailureKind.NETWORK, 429 to FailureKind.NETWORK, 503 to FailureKind.NETWORK)) {
            val error = IOException("callback", HttpDataSource.InvalidResponseCodeException(status, "fixture", null, emptyMap(), spec, byteArrayOf()))
            val failure = PlaybackErrorClassifier.classifyCode(6004, error, false)
            assertEquals("HTTP $status", expected, failure.kind)
            assertEquals(status, failure.httpStatus)
        }
    }
}
