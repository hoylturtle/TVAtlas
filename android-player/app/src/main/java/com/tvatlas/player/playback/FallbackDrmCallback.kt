package com.tvatlas.player.playback

import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.tvatlas.core.model.RouteTarget
import com.tvatlas.player.debug.DebugLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.UUID

/** License challenges stay on the authenticated media route. Only public device provisioning may fall back. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class FallbackDrmCallback(
    private val license: MediaDrmCallback,
    private val routes: List<RouteTarget>,
    private val provisioning: (RouteTarget) -> MediaDrmCallback,
    private val log: DebugLog? = null,
    private val active: () -> Boolean = { true },
) : MediaDrmCallback {
    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray =
        license.executeKeyRequest(uuid, request)

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        val url = request.defaultUrl.toHttpUrlOrNull()
        // Unknown provider URLs keep normal Media3 behavior, without replaying device data across routes.
        if (url == null || url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null ||
            !(url.host == "www.googleapis.com" || url.host == "widevine.com" || url.host.endsWith(".widevine.com")))
            return license.executeProvisionRequest(uuid, request)
        var last: Exception? = null
        for ((index, route) in routes.distinct().take(3).withIndex()) {
            if (!active() || Thread.currentThread().isInterrupted) throw IOException("设备初始化已取消")
            log?.event("INFO", "DRM_PROVISION_ROUTE", "attempt=${index + 1} route=${route.type} profile=${route.profile}")
            try {
                val bytes = provisioning(route).executeProvisionRequest(uuid, request)
                if (!active()) throw IOException("设备初始化已取消")
                log?.event("INFO", "DRM_PROVISION_ROUTE", "completed attempt=${index + 1}")
                return bytes
            } catch (error: Exception) {
                log?.error("DRM_PROVISION_ROUTE", error)
                if (!PlaybackErrorClassifier.transportFailure(error)) throw error
                last = error
            }
        }
        throw IOException("设备初始化的可用网络均失败", last)
    }
}
