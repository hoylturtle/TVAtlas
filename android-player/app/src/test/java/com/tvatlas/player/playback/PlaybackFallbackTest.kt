package com.tvatlas.player.playback

import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.core.player.FailureKind
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import javax.net.ssl.SSLHandshakeException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackFallbackTest {
    private val uuid = UUID.randomUUID()
    private val request = ExoMediaDrm.ProvisionRequest(byteArrayOf(1, 2), "https://www.googleapis.com/device?key=fixture")
    private fun callback(provision: () -> ByteArray, key: () -> ByteArray = { byteArrayOf(9) }) = object : MediaDrmCallback {
        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest) = provision()
        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest) = key()
    }
    @Test fun tlsProvisioningFailureTriesDirectButLicenseKeepsOriginalRoute() {
        val visited = mutableListOf<RouteTarget>()
        var licenses = 0
        val primary = RouteTarget.proxy("hk1")
        val callback = FallbackDrmCallback(callback({ fail("Provision must use independent callback"); byteArrayOf() }, { licenses++; byteArrayOf(9) }),
            listOf(primary, RouteTarget.DIRECT, RouteTarget.proxy("hk2")), { route -> callback({
                visited.add(route)
                if (route == primary) throw SSLHandshakeException("EOF")
                byteArrayOf(1)
            }, { fail("License must never switch to provisioning callback"); byteArrayOf() }) })
        assertArrayEquals(byteArrayOf(1), callback.executeProvisionRequest(uuid, request))
        assertEquals(listOf(primary, RouteTarget.DIRECT), visited)
        assertArrayEquals(byteArrayOf(9), callback.executeKeyRequest(uuid, ExoMediaDrm.KeyRequest(byteArrayOf(3), "https://license.test", 0)))
        assertEquals(1, licenses)
    }
    @Test fun permanentProvisioningRejectionDoesNotReplayAndRoutesAreBounded() {
        val visited = mutableListOf<RouteTarget>()
        val routes = (1..8).map { RouteTarget.proxy("hk$it") }
        val callback = FallbackDrmCallback(callback({ byteArrayOf() }), routes, { route -> callback({
            visited.add(route); throw SSLHandshakeException("EOF")
        }) })
        assertThrows(IOException::class.java) { callback.executeProvisionRequest(uuid, request) }
        assertEquals(3, visited.size)
        visited.clear()
        val rejected = FallbackDrmCallback(callback({ byteArrayOf() }), routes, { route -> callback({
            visited.add(route); throw IllegalArgumentException("device rejected")
        }) })
        assertThrows(IllegalArgumentException::class.java) { rejected.executeProvisionRequest(uuid, request) }
        assertEquals(1, visited.size)
    }
    @Test fun unknownProviderUsesOriginalCallbackWithoutRouteReplay() {
        val callback = FallbackDrmCallback(callback({ byteArrayOf(4) }), listOf(RouteTarget.DIRECT), {
            fail("Unknown endpoint must not be replayed"); callback({ byteArrayOf() })
        })
        assertArrayEquals(byteArrayOf(4), callback.executeProvisionRequest(uuid,
            ExoMediaDrm.ProvisionRequest(byteArrayOf(1), "https://www.googleapis.com.evil.test/create")))
    }
    @Test fun drmTransportCanFallBackButUnsupportedDevicesSkipTheSource() {
        val network = IOException("callback", java.util.concurrent.ExecutionException(SSLHandshakeException("EOF")))
        assertEquals(FailureKind.NETWORK, PlaybackErrorClassifier.classifyCode(6002, network, false).kind)
        assertEquals(FailureKind.DRM, PlaybackErrorClassifier.classifyCode(6001, IllegalStateException("unsupported"), false).kind)
        assertEquals(FailureKind.DRM, PlaybackErrorClassifier.classifyCode(6004, IOException("license rejected"), false).kind)
        assertEquals(FailureKind.MANIFEST, PlaybackErrorClassifier.classifyCode(3003, IOException("HTML"), false).kind)
    }
}
